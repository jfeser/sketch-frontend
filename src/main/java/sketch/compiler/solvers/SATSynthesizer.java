package streamit.frontend.solvers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.Map.Entry;

import streamit.frontend.CommandLineParamManager;
import streamit.frontend.controlflow.CFG;
import streamit.frontend.controlflow.CFGNode;
import streamit.frontend.controlflow.CFGNode.EdgePair;
import streamit.frontend.nodes.ExprArrayRange;
import streamit.frontend.nodes.ExprBinary;
import streamit.frontend.nodes.ExprConstInt;
import streamit.frontend.nodes.ExprFunCall;
import streamit.frontend.nodes.ExprNullPtr;
import streamit.frontend.nodes.ExprUnary;
import streamit.frontend.nodes.ExprVar;
import streamit.frontend.nodes.Expression;
import streamit.frontend.nodes.FEContext;
import streamit.frontend.nodes.FENode;
import streamit.frontend.nodes.FEReplacer;
import streamit.frontend.nodes.Function;
import streamit.frontend.nodes.Parameter;
import streamit.frontend.nodes.Program;
import streamit.frontend.nodes.Statement;
import streamit.frontend.nodes.StmtAssert;
import streamit.frontend.nodes.StmtAssign;
import streamit.frontend.nodes.StmtAtomicBlock;
import streamit.frontend.nodes.StmtBlock;
import streamit.frontend.nodes.StmtExpr;
import streamit.frontend.nodes.StmtFork;
import streamit.frontend.nodes.StmtIfThen;
import streamit.frontend.nodes.StmtReturn;
import streamit.frontend.nodes.StmtVarDecl;
import streamit.frontend.nodes.StreamSpec;
import streamit.frontend.nodes.StreamType;
import streamit.frontend.nodes.TempVarGen;
import streamit.frontend.nodes.Type;
import streamit.frontend.nodes.TypeArray;
import streamit.frontend.nodes.TypePrimitive;
import streamit.frontend.nodes.ExprArrayRange.Range;
import streamit.frontend.nodes.ExprArrayRange.RangeLen;
import streamit.frontend.parallelEncoder.AtomizeConditionals;
import streamit.frontend.parallelEncoder.BreakParallelFunction;
import streamit.frontend.parallelEncoder.CFGforPloop;
import streamit.frontend.parallelEncoder.ExtractPreParallelSection;
import streamit.frontend.parallelEncoder.VarSetReplacer;
import streamit.frontend.passes.CollectGlobalTags;
import streamit.frontend.passes.EliminateMultiDimArrays;
import streamit.frontend.passes.SimpleLoopUnroller;
import streamit.frontend.solvers.CEtrace.step;
import streamit.frontend.stencilSK.SimpleCodePrinter;
import streamit.frontend.tosbit.ValueOracle;
import streamit.frontend.tosbit.recursionCtrl.RecursionControl;




public class SATSynthesizer extends SATBackend implements Synthesizer {

	/**
	 * Original program
	 */
	Program prog;
	/**
	 *
	 * Function containing parallel sections. We assume this is the sketch.
	 */
	Function parfun;

	Program current;
	BreakParallelFunction parts;
	Set<StmtVarDecl> locals = new HashSet<StmtVarDecl>();
	Set<Object> globalTags;
	HashMap<String, Type> varTypes = new HashMap<String, Type>();

	ExprVar assumeFlag = new ExprVar((FENode)null, "_AF");

	/**
	 * Control flow graph
	 */
	CFG cfg;
	/**
	 *
	 * For each node in the control flow graph, it specifies the tags for all the statements contained in that node.
	 */
	Map<CFGNode, Set<Object>> nodeMap;
	/**
	 * Inverse of the nodeMap.
	 */
	Map<Object, CFGNode> invNodeMap;

	List<Statement> bodyl = new ArrayList<Statement>();
	Stack<List<Statement> > bodyStack = new Stack<List<Statement>>();

	Set<StmtVarDecl> globalDecls;

	VarSetReplacer[] localRepl;
	Queue<step>[] stepQueues;

	CFGNode[] lastNode = null;

	Set<String> globals = null;

	StmtFork ploop = null;

	int nthreads;

	public SATSynthesizer(Program prog_p, CommandLineParamManager params, RecursionControl rcontrol, TempVarGen varGen){
		super(params, rcontrol, varGen);
		this.prog = prog_p;


		this.prog = (Program)prog.accept(new SimpleLoopUnroller());

		ExtractPreParallelSection ps = new ExtractPreParallelSection();
		this.prog = (Program) prog.accept(ps);
		//this.prog.accept(new SimpleCodePrinter().outputTags());

		assert ps.parfun != null : "this is not a parallel sketch";

		parfun = ps.parfun;
		parts = new BreakParallelFunction();
		parfun.accept(parts);
        //prog.accept(new SimpleCodePrinter().outputTags());

		ploop = (StmtFork) parts.ploop.accept(new AtomizeConditionals(varGen));
		//ploop.accept(new SimpleCodePrinter());
		cfg = CFGforPloop.buildCFG(ploop, locals);
		nthreads = ploop.getIter().getIValue();

		locals.add( new StmtVarDecl(prog, TypePrimitive.inttype, "_ind", null) );
		nodeMap = CFGforPloop.tagSets(cfg);
		invNodeMap = new HashMap<Object, CFGNode>();
		for(Iterator<Entry<CFGNode, Set<Object>>> it = nodeMap.entrySet().iterator(); it.hasNext(); ){
			Entry<CFGNode, Set<Object>> e = it.next();
			for(Iterator<Object> oit = e.getValue().iterator(); oit.hasNext(); ){
				invNodeMap.put(oit.next(), e.getKey());
			}
		}
		globalDecls = new HashSet<StmtVarDecl>();
		globalDecls.addAll(parts.globalDecls);
		globalDecls.add(new StmtVarDecl(prog, TypePrimitive.bittype, assumeFlag.getName(), ExprConstInt.one));


		bodyl.addAll(globalDecls);


		CollectGlobalTags gtags = new CollectGlobalTags(parts.globalDecls);
		ploop.accept(gtags);
		globalTags = gtags.oset;
		globals = gtags.globals;


		for(StmtVarDecl svd : locals){
			for(int i=0; i<svd.getNumVars(); ++i){
				varTypes.put(svd.getName(i), svd.getType(i));
			}
		}




		localRepl = new VarSetReplacer[nthreads];
		stepQueues = new Queue[nthreads];
		for(int i=0; i<nthreads; ++i){
			localRepl[i] = new VarSetReplacer();
			populateVarReplacer(locals.iterator(), new ExprConstInt(i), localRepl[i]);
			stepQueues[i] = new LinkedList<step>();
		}
		lastNode  = new CFGNode[nthreads];
	}


	/**
	 * Populates the VarSetReplacer with replacement rules of the form:
	 * X -> X_p[idx].
	 *
	 * @param vars
	 * @param idx
	 * @param vrepl
	 */
	public void populateVarReplacer(Iterator<StmtVarDecl> vars, Expression idx, VarSetReplacer/*out*/ vrepl){
		while(vars.hasNext()){
			StmtVarDecl svd = vars.next();
			FENode cx = svd;
			for(int i=0; i<svd.getNumVars(); ++i){
				String oname = svd.getName(i);
				vrepl.addPair(oname, new ExprArrayRange(new ExprVar(cx, oname + "_p"), idx));
			}
		}
	}


	public FENode parametrizeLocals(FENode s, int thread){
		return (FENode) s.accept(localRepl[thread]);
	}


	public void addNode(CFGNode node, int thread){

		Statement s = null;

		if(node.isExpr()){
			s = (Statement) parametrizeLocals(node.getPreStmt(), thread);
		}

		if(node.isStmt()){
			s = (Statement) parametrizeLocals(node.getStmt(), thread);
		}

		if(s != null){
			pushBlock();
			addStatement(s, thread);
			popBlock();
		}
	}


	public Statement addAssume(Expression cond){
		return new StmtIfThen( cond, cond, new StmtAssign(assumeFlag, ExprConstInt.zero), null );
	}



	public Statement addAssume(CFGNode lastNode, int thread, EdgePair ep){
		Expression cond = new ExprBinary( new ExprArrayRange(new ExprVar(lastNode.getExpr(), "_ind_p"), new ExprConstInt(thread)), "==", new ExprConstInt(ep.label.intValue()));
		return addAssume(cond);
	}



	public CFGNode firstChildWithTag(CFGNode parent, int stmt){

		Queue<CFGNode> nqueue = new LinkedList<CFGNode>();
		if(parent != null){
			nqueue.add(parent);
		} else{
			nqueue.add(cfg.getEntry()) ;
		}
		CFGNode node = null;
		while(nqueue.size() > 0){
			CFGNode cur = nqueue.poll();
			Set<Object> so = nodeMap.get(cur);
			if( so.contains(stmt)  ){
				node = cur;
				break;
			}
			for(EdgePair ep : cur.getSuccs()){
				nqueue.add(ep.node);
			}
		}
		assert node != null;
		return node;

	}

	
	public CFGNode advanceUpTo(CFGNode node, int thread, CFGNode lastNode){				

		if(node != cfg.getEntry() && lastNode == null){
			lastNode = cfg.getEntry();
			addNode(lastNode, thread);
		}

		do{
			if(lastNode == null){ break; }
			if(lastNode.isExpr()){
				List<EdgePair> eplist = lastNode.getSuccs();
				List<Statement> assertStmts = new ArrayList<Statement>();
				boolean goodSucc = false;
				for(Iterator<EdgePair> it = eplist.iterator(); it.hasNext(); ){
					EdgePair ep = it.next();
					if(ep.node == node){
						goodSucc = true;
					}else{
						assertStmts.add(addAssume(lastNode, thread, ep));
					}
				}
				if(goodSucc){
					for(Iterator<Statement> it = assertStmts.iterator(); it.hasNext(); ){
						addStatement(it.next(), thread);
					}
					break;
				}else{
					assertStmts.clear();
					for(Iterator<EdgePair> it = eplist.iterator(); it.hasNext(); ){
						EdgePair ep = it.next();
						CFGNode nxt = ep.node;
						boolean found = false;
						CFGNode tmp = nxt;
						while(tmp.isStmt()){
							if(tmp == node){
								found = true;
								break;
							}
							tmp = tmp.getSuccs().get(0).node;
						}

						/*
						for(EdgePair ep2 : nxt.getSuccs()){
							if(ep2.node == node){
								found = true;
								break;
							}
						}
						*/
						if(found){
							goodSucc = true;
							lastNode = nxt;
							break;
						}else{
							assertStmts.add(addAssume(lastNode, thread, ep));
						}
					}
					assert goodSucc : "None of the successors matched";
					for(Iterator<Statement> it = assertStmts.iterator(); it.hasNext(); ){
						addStatement(it.next(), thread);
					}
					addNode(lastNode, thread);
				}
			}

			if(lastNode.isStmt()){
				List<EdgePair> eplist = lastNode.getSuccs();
				EdgePair succ = eplist.get(0);

				if(succ.node == node){
					break;
				}
				lastNode = succ.node;

				addNode(lastNode, thread);

			}
			assert lastNode != cfg.getExit() : "This is going to be an infinite loop";
		}while(true);
		
		return lastNode;
	}
	

	public CFGNode addBlock(int stmt, int thread, CFGNode lastNode){

		
		assert invNodeMap.containsKey(stmt);

		CFGNode node =  firstChildWithTag(lastNode, stmt);  //invNodeMap.get( stmt );

		assert node != null;

		if( node == lastNode  ){
			//Haven't advanced nodes, so I should stay here.
			return lastNode;
		}


		advanceUpTo(node, thread, lastNode);
		
		
		addNode(node, thread);

		return node;
	}


	public void pushBlock(){

		bodyStack.push(bodyl);
		bodyl = new ArrayList<Statement>();
	}
	public void popBlock(){
		List<Statement> tmpl = bodyStack.pop();
		tmpl.add( new StmtBlock((FENode)null, bodyl));
		bodyl = tmpl;
	}


	private Statement nodeReadyToExecute(CFGNode n, int thread, final Expression indVar){

		Statement s = null;
		if(n.isExpr()){ s = n.getPreStmt(); }
		if(n.isStmt()){ s = n.getStmt(); }

		class modifiedLocals extends FEReplacer{
			boolean isLeft = false;
			public HashSet<String> locals = new HashSet<String>();
			private boolean globalTaint = false;
			private HashSet<String> modLocals = new HashSet<String>();
			public Object visitStmtAssign(StmtAssign stmt){
				boolean tmpLeft = isLeft;
			 	isLeft = true;
			 	boolean gt = globalTaint;
			 	globalTaint = false;
		        Expression newLHS = doExpression(stmt.getLHS());
		        isLeft = tmpLeft;
		        boolean newgt = globalTaint;
		        globalTaint = gt;
		        Expression newRHS = doExpression(stmt.getRHS());
		        if(newgt){
		        	return null;
		        }
		        return stmt;
			}

			public Object visitStmtAssert(StmtAssert stmt){
				return new StmtIfThen(stmt, assumeFlag, stmt, null);
			}

			public Object visitExprArrayRange(ExprArrayRange exp) {
				boolean tmpLeft = isLeft;

				// This is weird, but arrays can't be parameters to functions in
				// Promela.  So we'll be conservative and always treat them as
				// LHS expressions.
				isLeft = true;
				doExpression(exp.getBase());
				isLeft = tmpLeft;


				final List l=exp.getMembers();
				for(int i=0;i<l.size();i++) {
					Object obj=l.get(i);
					if(obj instanceof Range) {
						Range range=(Range) obj;
						tmpLeft = isLeft;
					 	isLeft = false;
						doExpression(range.start());
						doExpression(range.end());
						isLeft = tmpLeft;
					}
					else if(obj instanceof RangeLen) {
						RangeLen range=(RangeLen) obj;
						tmpLeft = isLeft;
					 	isLeft = false;
						doExpression(range.start());
						isLeft = tmpLeft;
					}
				}
				return exp;
			}




			public Object visitStmtAtomicBlock(StmtAtomicBlock stmt){
				if(stmt.isCond()){
					return new StmtAssign(indVar, new ExprBinary(indVar , "&&",  stmt.getCond()));
				}
				return super.visitStmtAtomicBlock(stmt);
			}



			public Object visitStmtVarDecl(StmtVarDecl stmt)
		    {
		        for (int i = 0; i < stmt.getNumVars(); i++)
		        {
		            Expression init = stmt.getInit(i);
		            if (init != null)
		                init = doExpression(init);
		            Type t = (Type) stmt.getType(i).accept(this);
		            locals.add(stmt.getName(i));
		        }
		        return stmt;
		    }

			public Object visitExprVar(ExprVar exp) {
				if(isLeft){
					String nm = exp.getName();
					if(globals.contains(nm)){
						globalTaint = true;
					}else{
						if(!locals.contains(nm)){
							modLocals.add(nm);
						}
					}
				}
				return exp;
			}

		}


		class addTemporaries extends FEReplacer{
			private final HashSet<String> modLocals;
			public addTemporaries(HashSet<String> modLocals){
				this.modLocals = modLocals;
			}
			public Object visitExprVar(ExprVar exp) {
				String nm = exp.getName();
				if(modLocals.contains( nm )){
					return new ExprVar(exp, nm + "__t");
				}
				return exp;
			}
		}

		modifiedLocals ml = new modifiedLocals();
		s = s.doStatement(ml);
		s = s.doStatement(new addTemporaries(ml.modLocals));

		List<Statement> ls = new ArrayList<Statement>();
		for(String vn : ml.modLocals){
			ls.add(new StmtVarDecl(s, varTypes.get(vn), vn + "__t", new ExprVar(s, vn)));
		}
		ls.add(s);
		s = (Statement) parametrizeLocals(new StmtBlock(s, ls), thread);

		return s;
	}






	private Expression getAtomicCond(CFGNode n, int thread){

		Statement s = null;
		if(n.isExpr()){ s = n.getPreStmt(); }
		if(n.isStmt()){ s = n.getStmt(); }

		nodeReadyToExecute(n, thread, new ExprVar(s, "IV"));

		final List<Expression> answer = new ArrayList<Expression>();

		class hasAtomic extends FEReplacer{
			boolean assignOnPath = false;
			Stack<Expression> estack = new Stack<Expression>();
			@Override
			public Object visitStmtAssign(StmtAssign stmt){
				assignOnPath = true;
				return stmt;
			}

			@Override
			public Object visitStmtIfThen(StmtIfThen stmt){
				estack.add(new ExprUnary("!", stmt.getCond()));
				boolean tmp = assignOnPath;
				stmt.getCons().accept(this);
				estack.pop();
				boolean tmp2 = assignOnPath;
				assignOnPath = tmp;
				if(stmt.getAlt() != null){
					estack.add(stmt.getCond());
					stmt.getAlt().accept(this);
					estack.pop();
				}
				assignOnPath = assignOnPath || tmp2;
				return stmt;
			}

			@Override
			public Object visitStmtAtomicBlock(StmtAtomicBlock stmt){
				if(stmt.isCond()){
					assert !assignOnPath : "assignments before atomics NYI";
					Expression c = stmt.getCond() ;
					for(Expression e : estack){
						c = new ExprBinary(c, "||", e);
					}
					if(answer.size() > 0){
						answer.set(0, new ExprBinary(c, "&&", answer.get(0)  ) );
					}else{
						answer.add(c);
					}
				}
				assignOnPath = true;
				return stmt;
			}
		}

		s.accept(new hasAtomic());

		if(answer.size() > 0){
			return (Expression)parametrizeLocals(answer.get(0), thread);
		}

		return ExprConstInt.one;
	}

/**
 *
 * Adds to the list ls the code to check whether the successor of
 * node n is ready to execute or not.
 *
 * @param n
 * @param thread
 * @param iv Indicator variable that says whether this thread is
 *   ready to execute or not.
 * @param ls
 */
	private void getNextAtomicCond(CFGNode n, int thread, ExprVar iv, List<Statement> ls){

		if(n.isStmt()){
			CFGNode nxt = n.getSuccs().get(0).node;

			if(nxt == cfg.getExit()){
				ls.add(new StmtAssign(iv, ExprConstInt.zero));
				return;
			}else{
				ls.add(nodeReadyToExecute(nxt, thread, iv));
				return ;
			}
		}

		if(n.isExpr()){
			for( EdgePair ep : n.getSuccs() ){
				Statement s;
				if(ep.node == cfg.getExit()){
					s = new StmtAssign(iv, ExprConstInt.zero);
				}else{
					s = nodeReadyToExecute(ep.node, thread, iv);
				}
				Expression g = new ExprBinary((Expression)parametrizeLocals(n.getExpr(), thread), "==", new ExprConstInt(ep.label));

				ls.add(new StmtIfThen(s, g, s, null));

			}
			return;
		}
		return;

	}




	private void finalAdjustment(CFGNode node, int thread){

		if(node.isStmt()){
			addNode(node.getSuccs().get(0).node, thread);
			lastNode[thread] = node.getSuccs().get(0).node;
		}

		if(node.isExpr()){
			assert false : "NYI Don't know how to modify lastNode.";
			for(EdgePair ep : node.getSuccs()){

				Expression cond = new ExprBinary((Expression)parametrizeLocals(node.getExpr(), thread), "==", new ExprConstInt(ep.label));

				Statement s = null;
				if(ep.node.isStmt()){
					s = ep.node.getStmt();
				}
				if(ep.node.isExpr()){
					s = ep.node.getPreStmt();
				}
				s = (Statement) parametrizeLocals(s, thread);
				s = preprocStatement(s, thread);
				bodyl.add(new StmtIfThen(cond, cond, s, null));

			}



		}


	}



	private Statement preprocStatement(Statement s, final int thread){

		class PreprocStmt extends FEReplacer{
			@Override
			public Object visitStmtAssert(StmtAssert stmt){
				return new StmtIfThen(stmt, assumeFlag, stmt, null);
			}
			Stack<StmtAtomicBlock> atomicCheck = new Stack<StmtAtomicBlock>();
			public Object visitStmtAtomicBlock(StmtAtomicBlock stmt){

				if(stmt.isCond()){
					assert atomicCheck.size() == 0 : "Conditional atomic can not be inside another atomic." + stmt.getCx();
					Statement s2 = stmt.getBlock().doStatement(this);



					Statement elsecond;

					if(thread >=0){

						List<Statement> mls = new ArrayList<Statement>();
						mls.add(new StmtVarDecl(stmt, TypePrimitive.bittype, "mIV", ExprConstInt.zero));
						ExprVar miv = new ExprVar(stmt, "mIV");
						for(int i=0; i<nthreads; ++i){
							if(i == thread){ continue; }
							List<Statement> ls = new ArrayList<Statement>();
							ls.add(new StmtVarDecl(stmt, TypePrimitive.bittype, "IV", ExprConstInt.one));
							ExprVar iv = new ExprVar(stmt, "IV");
							CFGNode n = lastNode[i];
							if(n != null){
								getNextAtomicCond(n, i, iv, ls);
							}else{
								ls.add(nodeReadyToExecute(cfg.getEntry(), i, iv));
							}
							ls.add(new StmtAssign(miv, new ExprBinary(miv, "||", iv)));
							mls.add(new StmtBlock(stmt, ls));
						}

						Statement allgood = new StmtAssign(assumeFlag, ExprConstInt.zero);
						Statement allbad = new StmtIfThen(stmt, assumeFlag,
								new StmtAssert(stmt, ExprConstInt.zero, "There was a deadlock."), null);

						Statement otherwise = new StmtIfThen(stmt, miv, allgood, allbad);
						mls.add(otherwise);
						elsecond = new StmtBlock(stmt, mls);
					}else{
						elsecond = new StmtAssert(stmt, ExprConstInt.zero, "There was a deadlock.");
					}
					Statement s = new StmtIfThen(stmt, stmt.getCond(), s2, elsecond);
					return s;
				}else{
					atomicCheck.add(stmt);
					Object o = super.visitStmtAtomicBlock(stmt);
					atomicCheck.pop();
					return o;
				}
			}

		};
		return s.doStatement(new PreprocStmt());
	}


	public void addStatement(Statement s, final int thread){
		bodyl.add(preprocStatement(s, thread));
	}

	@SuppressWarnings("unchecked")
	public void closeCurrent(){


		List<Parameter> outPar = new ArrayList<Parameter>();
		String opname = null;
		List<Statement> lst = new ArrayList<Statement>();

		for(Iterator<Parameter> it = parfun.getParams().iterator(); it.hasNext(); ){
			Parameter p = it.next();
			if(p.isParameterOutput()){
				outPar.add(p);
				opname = p.getName();
			}else{
				lst.add(new StmtVarDecl(p, p.getType(), p.getName(), ExprConstInt.zero ));
			}
		}


		lst.addAll(bodyl);
		lst.add( new StmtAssign(new ExprVar(current, opname), ExprConstInt.one) );

		Statement body = new StmtBlock(current, lst);

		Function spec = Function.newHelper(current, "spec", TypePrimitive.inttype ,outPar, new StmtAssign(new ExprVar(current, opname), ExprConstInt.one));

		Function sketch = Function.newHelper(current, "sketch", TypePrimitive.inttype ,
				outPar, "spec", body);

		List<Function> funcs = new ArrayList<Function>();

		funcs.add(spec);
		funcs.add(sketch);

		List<StreamSpec> streams = Collections.singletonList(
				new StreamSpec(current, StreamSpec.STREAM_FILTER, null, "MAIN",Collections.EMPTY_LIST , Collections.EMPTY_LIST ,funcs));
		current = new Program(current,streams, Collections.EMPTY_LIST);


	}


	/**
	 * The input collection contains variable declarations, and the output collection contains variable' declarations such that
	 * if T X; is a declaration in the original list, T[NTHREADS] X_p will be a declaration in the output list.
	 *
	 * @param original input list
	 * @param nthreads number of threads.
	 */
	public void declArrFromScalars(Iterator<StmtVarDecl> original,   Expression nthreads){
		while(original.hasNext()){
			StmtVarDecl svd = original.next();
			FENode cx = svd;
			for(int i=0; i<svd.getNumVars(); ++i){
				String oname = svd.getName(i);
				Type ot = svd.getType(i);
				//assert svd.getInit(i) == null : "At this stage, declarations shouldn't have initializers";
				String nname = oname + "_p";
				Type nt = new TypeArray(ot, nthreads);
				Expression init ;

				Type base = nt;
				while(base instanceof TypeArray){
					base = ((TypeArray)base).getBase();
				}
				if(base instanceof TypePrimitive){
					init = ExprConstInt.zero;
				}else{
					init = ExprNullPtr.nullPtr;
				}
				addStatement(new StmtVarDecl(cx, nt, nname, init), -1);
			}
		}
	}


	public void mergeWithCurrent(CEtrace trace){
		/* if (reallyVerbose ())
			prog.accept(new SimpleCodePrinter().outputTags()); */
		pushBlock();

		if(current != null){
			for(Iterator<StmtVarDecl> it = globalDecls.iterator(); it.hasNext(); ){
				StmtVarDecl svd = it.next();
				for(int i=0; i<svd.getNumVars(); ++i){
					if(svd.getInit(i)!= null){
						addStatement(new StmtAssign(new ExprVar(svd, svd.getName(i)), svd.getInit(i)), -1);
					}
				}
			}
		}

		addStatement(parts.prepar, -1);

		declArrFromScalars(locals.iterator(), new ExprConstInt(nthreads));

		for(int i=0; i<nthreads; ++i){
			Expression idx = new ExprConstInt(i) ;
			Expression ilhs = new ExprArrayRange( new ExprVar(idx, ploop.getLoopVarName()  +"_p")  , idx  );
			addStatement(new StmtAssign(ilhs, idx ) , i);
		}


		List<step> l = trace.steps;
		
		/**
		 * It's tempting to believe that at this point, we should just append
		 * trace.blickedSteps to l, and that's that, but that will mess up with 
		 * deadlock detection. The reason is that before adding the blocked steps, 
		 * we should empty the stepQueues.
		 * 
		 */
		
		Iterator<step> sit = l.iterator();
		step cur;


		for(int i=0; i<nthreads; ++i){ lastNode[i] = null; }

		StringBuffer sbuf = new StringBuffer();

		while(sit.hasNext()){
			cur= sit.next();
			if(cur.thread>0){
				sbuf.append(""+ cur);
				if( invNodeMap.containsKey(cur.stmt) ){
					int thread = cur.thread-1;
					stepQueues[thread].add(cur);
					//System.out.println("C " + cur);
					if( globalTags.contains(cur.stmt)  ){
						Queue<step> qs = stepQueues[thread];
						while( qs.size() > 0  ){
							step tmp = qs.remove();
						//	System.out.println("t " + tmp);
							lastNode[thread] = addBlock(tmp.stmt, thread, lastNode[thread]);
						}
					}
				}else{
					// System.out.println("NC " + cur);
				}


			}
		}

		log(sbuf.toString());
		if(schedules.containsKey(sbuf.toString())){
			throw new RuntimeException("I just saw a repeated schedule.");
		}
		schedules.put(sbuf.toString(), schedules.size());

		for(int thread=0; thread<nthreads; ++thread){
			Queue<step> qs = stepQueues[thread];
			while( qs.size() > 0  ){
				step tmp = qs.remove();
				lastNode[thread] = addBlock(tmp.stmt, thread, lastNode[thread]);
			}
		}

		for(step s : trace.blockedSteps){
			int thread = s.thread-1;
			int stmt = s.stmt;
			
			assert invNodeMap.containsKey(stmt);

			CFGNode node =  firstChildWithTag(lastNode[thread], stmt);  //invNodeMap.get( stmt );

			assert node != null;

			if( node != lastNode[thread]  ){
				//Haven't advanced nodes, so I should stay here.
				lastNode[thread] = advanceUpTo(node, thread, lastNode[thread]);	
			}
		}
		
		boolean allEnd = true;

		for(int thread=0; thread<nthreads; ++thread){
			if(lastNode[thread]!= null && lastNode[thread] != cfg.getExit()){

				boolean tmp = procLastNodes(lastNode[thread], thread);
				allEnd = allEnd && tmp;
			}
			if(lastNode[thread] == null){
				allEnd = false;
			}
		}

		if(allEnd){
			addStatement(parts.postpar, -1);
		}
		popBlock();
		closeCurrent();
	}

	Map<String, Integer> schedules = new HashMap<String, Integer>();




	boolean procLastNodes(CFGNode node, int thread){
		boolean someEnd = false;
		List<Statement> assertStmts = new ArrayList<Statement>();
		for(EdgePair ep : node.getSuccs()){
			if(ep.node == cfg.getExit() && ep.node.isEmpty()){
				someEnd = true;
			}else{
				if(node.isExpr()){
					assertStmts.add(addAssume(node, thread, ep));
				}

			}
		}
		if(!someEnd){
			finalAdjustment(node, thread);
		}else{
			for(Iterator<Statement> it = assertStmts.iterator(); it.hasNext(); ){
				addStatement(it.next(), thread);
			}
		}
		return someEnd;

	}






	public ValueOracle nextCandidate(CounterExample counterExample) {

		mergeWithCurrent((CEtrace)counterExample);

		current = (Program)current.accept(new EliminateMultiDimArrays());
		 if (reallyVerbose ())
			current.accept(new SimpleCodePrinter());
		boolean tmp = partialEvalAndSolve(current);

		return tmp ? getOracle() : null;
	}



	protected boolean reallyVerbose () {
		return params.flagValue ("verbosity") >= 5;
	}

}
