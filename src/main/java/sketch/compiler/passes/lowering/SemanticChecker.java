/*
 * Copyright 2003 by the Massachusetts Institute of Technology.
 *
 * Permission to use, copy, modify, and distribute this
 * software and its documentation for any purpose and without
 * fee is hereby granted, provided that the above copyright
 * notice appear in all copies and that both that copyright
 * notice and this permission notice appear in supporting
 * documentation, and that the name of M.I.T. not be used in
 * advertising or publicity pertaining to distribution of the
 * software without specific, written prior permission.
 * M.I.T. makes no representations about the suitability of
 * this software for any purpose.  It is provided "as is"
 * without express or implied warranty.
 */

package streamit.frontend.passes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import streamit.frontend.controlflow.CFG;
import streamit.frontend.controlflow.CFGBuilder;
import streamit.frontend.controlflow.CFGNode;
import streamit.frontend.controlflow.CountLattice;
import streamit.frontend.controlflow.DataFlow;
import streamit.frontend.controlflow.Lattice;
import streamit.frontend.controlflow.StatementCounter;
import streamit.frontend.controlflow.StrictTypeLattice;
import streamit.frontend.nodes.ExprArray;
import streamit.frontend.nodes.ExprArrayInit;
import streamit.frontend.nodes.ExprArrayRange;
import streamit.frontend.nodes.ExprBinary;
import streamit.frontend.nodes.ExprConstInt;
import streamit.frontend.nodes.ExprField;
import streamit.frontend.nodes.ExprFunCall;
import streamit.frontend.nodes.ExprPeek;
import streamit.frontend.nodes.ExprPop;
import streamit.frontend.nodes.ExprTernary;
import streamit.frontend.nodes.ExprUnary;
import streamit.frontend.nodes.ExprVar;
import streamit.frontend.nodes.Expression;
import streamit.frontend.nodes.FEContext;
import streamit.frontend.nodes.FENode;
import streamit.frontend.nodes.FEReplacer;
import streamit.frontend.nodes.FieldDecl;
import streamit.frontend.nodes.FuncWork;
import streamit.frontend.nodes.Function;
import streamit.frontend.nodes.Parameter;
import streamit.frontend.nodes.Program;
import streamit.frontend.nodes.SCAnon;
import streamit.frontend.nodes.SCSimple;
import streamit.frontend.nodes.Statement;
import streamit.frontend.nodes.StmtAdd;
import streamit.frontend.nodes.StmtAssert;
import streamit.frontend.nodes.StmtAssign;
import streamit.frontend.nodes.StmtBody;
import streamit.frontend.nodes.StmtDoWhile;
import streamit.frontend.nodes.StmtEmpty;
import streamit.frontend.nodes.StmtEnqueue;
import streamit.frontend.nodes.StmtExpr;
import streamit.frontend.nodes.StmtFor;
import streamit.frontend.nodes.StmtIfThen;
import streamit.frontend.nodes.StmtJoin;
import streamit.frontend.nodes.StmtLoop;
import streamit.frontend.nodes.StmtPhase;
import streamit.frontend.nodes.StmtPush;
import streamit.frontend.nodes.StmtReturn;
import streamit.frontend.nodes.StmtSendMessage;
import streamit.frontend.nodes.StmtSplit;
import streamit.frontend.nodes.StmtVarDecl;
import streamit.frontend.nodes.StmtWhile;
import streamit.frontend.nodes.StreamCreator;
import streamit.frontend.nodes.StreamSpec;
import streamit.frontend.nodes.StreamType;
import streamit.frontend.nodes.SymbolTable;
import streamit.frontend.nodes.Type;
import streamit.frontend.nodes.TypeArray;
import streamit.frontend.nodes.TypePrimitive;
import streamit.frontend.nodes.TypeStruct;
import streamit.frontend.nodes.UnrecognizedVariableException;
import streamit.frontend.nodes.ExprArrayRange.Range;
import streamit.frontend.nodes.ExprArrayRange.RangeLen;

/**
 * Perform checks on the semantic correctness of a StreamIt program.
 * The main entry point to this class is the static
 * <code>streamit.frontend.passes.SemanticChecker.check</code> method,
 * which returns <code>true</code> if a program has no detected
 * semantic errors.
 *
 * @author  David Maze &lt;dmaze@cag.lcs.mit.edu&gt;
 * @version $Id$
 */
public class SemanticChecker
{
	/**
	 * Check a StreamIt program for semantic correctness.  This
	 * returns <code>false</code> and prints diagnostics to standard
	 * error if errors are detected.
	 *
	 * @param prog  parsed program object to check
	 * @returns     <code>true</code> if no errors are detected
	 */
	public static boolean check(Program prog)
	{    	
		SemanticChecker checker = new SemanticChecker();
		Map streamNames = checker.checkStreamNames(prog);
		checker.checkDupFieldNames(prog, streamNames);
		checker.checkStatementPlacement(prog);
		checker.checkVariableUsage(prog);
		checker.checkBasicTyping(prog);
		checker.checkStatementCounts(prog);
		return checker.good;
	}

	// true if we haven't found any errors
	protected boolean good;

	protected void report(FENode node, String message)
	{
		report(node.getContext(), message);
	}

	protected void report(FEContext ctx, String message)
	{
		good = false;
		System.err.println(ctx + ": " + message);
	}

	public SemanticChecker()
	{
		good = true;
	}

	/**
	 * Checks that the provided program does not have duplicate names
	 * of structures or streams.
	 *
	 * @param prog  parsed program object to check
	 * @returns a map from structure names to <code>FEContext</code>
	 *          objects showing where they are declared
	 */
	public Map checkStreamNames(Program prog)
	{
		Map names = new HashMap(); // maps names to FEContexts

		//System.out.println("checkStreamNames");

		// Add built-in streams:
		FEContext ctx = new FEContext("<built-in>");
		names.put("Identity", ctx);
		names.put("FileReader", ctx);
		names.put("FileWriter", ctx);

		for (Iterator iter = prog.getStreams().iterator(); iter.hasNext(); )
		{
			StreamSpec spec = (StreamSpec)iter.next();
			checkAStreamName(names, spec.getName(), spec.getContext());
		}

		for (Iterator iter = prog.getStructs().iterator(); iter.hasNext(); )
		{
			TypeStruct ts = (TypeStruct)iter.next();
			checkAStreamName(names, ts.getName(), ts.getContext());
		}
		return names;
	}

	private void checkAStreamName(Map map, String name, FEContext ctx)
	{
		if (map.containsKey(name))
		{
			FEContext octx = (FEContext)map.get(name);
			report(ctx, "Multiple declarations of '" + name + "'");
			report(octx, "as a stream or structure");
		}
		else
		{
			map.put(name, ctx);
		}
	}

	/**
	 * Checks that no structures have duplicated field names.  In
	 * particular, a field in a structure or filter can't have the
	 * same name as another field in the same structure or filter, and
	 * can't have the same name as a stream or structure.
	 *
	 * @param prog  parsed program object to check
	 * @param streamNames  map from top-level stream and structure
	 *              names to FEContexts in which they are defined
	 */
	public void checkDupFieldNames(Program prog, Map streamNames)
	{
		//System.out.println("checkDupFieldNames");

		for (Iterator iter = prog.getStreams().iterator(); iter.hasNext(); )
		{
			StreamSpec spec = (StreamSpec)iter.next();
			Map localNames = new HashMap();
			Iterator i2;
			for (i2 = spec.getParams().iterator(); i2.hasNext(); )
			{
				Parameter param = (Parameter)i2.next();
				checkADupFieldName(localNames, streamNames,
						param.getName(), spec.getContext());
			}
			for (i2 = spec.getVars().iterator(); i2.hasNext(); )
			{
				FieldDecl field = (FieldDecl)i2.next();
				for (int i = 0; i < field.getNumFields(); i++)
					checkADupFieldName(localNames, streamNames,
							field.getName(i), field.getContext());
			}
			for (i2 = spec.getFuncs().iterator(); i2.hasNext(); )
			{
				Function func = (Function)i2.next();
				// Some functions get alternate names if their real
				// name is null:
				String name = func.getName();
				if (name == null)
				{
					switch(func.getCls())
					{
					case Function.FUNC_INIT: name = "init"; break;
					case Function.FUNC_WORK: name = "work"; break;
					case Function.FUNC_PREWORK: name = "prework"; break;
					case Function.FUNC_HANDLER:
						report(func, "message handlers must have names");
						break;
					case Function.FUNC_HELPER:
						report(func, "helper functions must have names");
						break;
					case Function.FUNC_PHASE:
						report(func, "phase functions must have names");
						break;
					default:
						// is BUILTIN_HELPER and CONST_HELPER.  Ignore
					}
				}
				if (name != null)
					checkADupFieldName(localNames, streamNames,
							name, func.getContext());
			}
		}
		for (Iterator iter = prog.getStructs().iterator(); iter.hasNext(); )
		{
			TypeStruct ts = (TypeStruct)iter.next();
			Map localNames = new HashMap();
			for (int i = 0; i < ts.getNumFields(); i++)
				checkADupFieldName(localNames, streamNames,
						ts.getField(i), ts.getContext());
		}
	}

	private void checkADupFieldName(Map localNames, Map streamNames,
			String name, FEContext ctx)
	{
		if (localNames.containsKey(name))
		{
			FEContext octx = (FEContext)localNames.get(name);
			report(ctx, "Duplicate declaration of '" + name + "'");
			report(octx, "(also declared here)");
		}
		else
		{
			localNames.put(name, ctx);
			if (streamNames.containsKey(name))
			{
				FEContext octx = (FEContext)streamNames.get(name);
				report(ctx, "'" + name + "' has the same name as");
				report(octx, "a stream or structure");
			}
		}
	}

	/**
	 * Checks that statements exist in valid contexts for the type of
	 * statement.  This checks that add, split, join, loop, and body
	 * statements are only in appropriate initialization code, and
	 * that push, pop, peek, and keep statements are only in
	 * appropriate work function code.
	 *
	 * @param prog  parsed program object to check
	 */
	public void checkStatementPlacement(Program prog)
	{
		//System.out.println("checkStatementPlacement");

		// This is easiest to implement as a visitor, since otherwise
		// we'll end up trying to recursively call ourselves if there's
		// an anonymous stream declaration.  Ick.
		prog.accept(new FEReplacer() {
			// Track the current streamspec and function:
			private StreamSpec spec = null;
			private Function func = null;

			
			public Object visitStmtFor(StmtFor stmt)
			{
				// check the condition
				
				if( stmt.getInit() == null){
					report(stmt, "For loops without initializer not supported." );
				}
				return super.visitStmtFor(stmt);
			}
			
			public Object visitStreamSpec(StreamSpec ss)
			{
				//System.out.println("checkStatementPlacement::visitStreamSpec");

				//9assert false;

				StreamSpec oldSpec = spec;
				spec = ss;
				Object result = super.visitStreamSpec(ss);
				spec = oldSpec;
				return result;
			}

			public Object visitFunction(Function func2)
			{	
				Function oldFunc = func;
				func = func2;
				Object result = super.visitFunction(func2);
				func = oldFunc;
				return result;
			}

			public Object visitFuncWork(FuncWork func2)
			{
				Function oldFunc = func;
				func = func2;
				Object result = super.visitFuncWork(func2);
				func = oldFunc;
				return result;
			}

			// So the remainder of this just needs to check
			// spec.getType() and func.getCls() and that they're
			// correct vs. the type of statement.
			public Object visitStmtAdd(StmtAdd stmt)
			{
				if ((func.getCls() != Function.FUNC_INIT) ||
						(spec.getType() != StreamSpec.STREAM_PIPELINE &&
								spec.getType() != StreamSpec.STREAM_SPLITJOIN))
					report(stmt,
							"add statement only allowed " +
					"in pipeline/splitjoin");
				return super.visitStmtAdd(stmt);
			}

			public Object visitStmtBody(StmtBody stmt)
			{
				if (func.getCls() != Function.FUNC_INIT ||
						spec.getType() != StreamSpec.STREAM_FEEDBACKLOOP)
					report(stmt,
							"body statement only allowed " +
					"in feedbackloop");
				return super.visitStmtBody(stmt);
			}

			public Object visitStmtEnqueue(StmtEnqueue stmt)
			{
				assert false;

				if (func.getCls() != Function.FUNC_INIT ||
						spec.getType() != StreamSpec.STREAM_FEEDBACKLOOP)
					report(stmt,
							"enqueue statement only allowed " +
					"in feedbackloop");
				return super.visitStmtEnqueue(stmt);
			}

			public Object visitStmtJoin(StmtJoin stmt)
			{
				assert false;

				if ((func.getCls() != Function.FUNC_INIT) ||
						(spec.getType() != StreamSpec.STREAM_FEEDBACKLOOP &&
								spec.getType() != StreamSpec.STREAM_SPLITJOIN))
					report(stmt,
							"join statement only allowed " +
					"in splitjoin/feedbackloop");
				return super.visitStmtJoin(stmt);
			}

			public Object visitExprPeek(ExprPeek expr)
			{
				assert false;

				if ((func.getCls() != Function.FUNC_PHASE &&
						func.getCls() != Function.FUNC_PREWORK &&
						func.getCls() != Function.FUNC_WORK) ||
						(spec.getType() != StreamSpec.STREAM_FILTER))
					report(expr,
							"peek expression only allowed " +
					"in filter work functions");
				return super.visitExprPeek(expr);
			}

			public Object visitExprPop(ExprPop expr)
			{
				assert false;

				if ((func.getCls() != Function.FUNC_PHASE &&
						func.getCls() != Function.FUNC_PREWORK &&
						func.getCls() != Function.FUNC_WORK) ||
						(spec.getType() != StreamSpec.STREAM_FILTER))
					report(expr,
							"pop expression only allowed " +
					"in filter work functions");
				return super.visitExprPop(expr);
			}

			public Object visitStmtPush(StmtPush stmt)
			{
				assert false;

				if ((func.getCls() != Function.FUNC_PHASE &&
						func.getCls() != Function.FUNC_PREWORK &&
						func.getCls() != Function.FUNC_WORK) ||
						(spec.getType() != StreamSpec.STREAM_FILTER))
					report(stmt,
							"push statement only allowed " +
					"in filter work functions");
				return super.visitStmtPush(stmt);
			}

			public Object visitStmtSplit(StmtSplit stmt)
			{
				assert false;

				if ((func.getCls() != Function.FUNC_INIT) ||
						(spec.getType() != StreamSpec.STREAM_FEEDBACKLOOP &&
								spec.getType() != StreamSpec.STREAM_SPLITJOIN))
					report(stmt,
							"split statement only allowed " +
					"in splitjoin/feedbackloop");
				return super.visitStmtSplit(stmt);
			}
		});
	}

	/**
	 * Checks that basic operations are performed on appropriate types.
	 * For example, the type of the unary ! operator can't be float
	 * or complex; there needs to be a common type for equality
	 * checking, but an arithmetic type for arithmetic operations.
	 * This also tests that the right-hand side of an assignment is
	 * assignable to the left-hand side.
	 *<p>
	 * (Does this test that peek, pop, push, and enqueue are used
	 * properly?  Initial plans were to have this as a separate
	 * function, but it does fit nicely here.)
	 *
	 * @param prog  parsed program object to check
	 */
	public void checkBasicTyping(Program prog)
	{
		/* We mostly just need to walk through and check things.
		 * enqueue statements can be hard, though: if there's a
		 * feedback loop with void type, we need to find the
		 * loopback type, which is the output type of the loop
		 * stream object.  If it's kosher to have enqueue before
		 * loop, we need an extra pass over statements to find
		 * the loop type.  The AssignLoopTypes pass could be
		 * helpful here, but we want to give an error message if
		 * things fail. */
		prog.accept(new SymbolTableVisitor(null) {
			// Need to visit everything.  Handily, STV does things
			// for us like save streamType when we run across
			// streamspecs; the only potentially hard thing is
			// the loop type of feedback loops.
			//
			// Otherwise, assume that the GetExprType pass can
			// find the types of things.  This should report
			// an error exactly when GET returns null,
			// so we can ignore nulls (assume that they type
			// check).
			public Object visitExprUnary(ExprUnary expr)
			{
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitExprUnary");

				Type ot = getType(expr.getExpr());
				boolean isArr = false;
				if(ot instanceof TypeArray){
					ot = ((TypeArray)ot).getBase();
					isArr = true;
				}
				if (ot != null)
				{
					Type inttype =
						new TypePrimitive(TypePrimitive.TYPE_INT);
					Type bittype =
						new TypePrimitive(TypePrimitive.TYPE_BIT);

					switch(expr.getOp())
					{
					case ExprUnary.UNOP_NEG:
					case ExprUnary.UNOP_BNOT:
						// you can negate a bit, since 0 and 1
						// literals always count as bits.
						// However, the resulting negation will be
						// an int.
						if (!bittype.promotesTo(ot)) 
							report(expr, "cannot negate " + ot);
						break;

					case ExprUnary.UNOP_NOT:
						if (!ot.promotesTo(bittype))
							report(expr, "cannot take boolean not of " +
									ot);
						break;

					case ExprUnary.UNOP_PREDEC:
					case ExprUnary.UNOP_PREINC:
					case ExprUnary.UNOP_POSTDEC:
					case ExprUnary.UNOP_POSTINC:
						// same as negation, regarding bits
						if (!bittype.promotesTo(ot))
							report(expr, "cannot perform ++/-- on " + ot);
						break;
					}
				}

				return super.visitExprUnary(expr);
			}
			
			private Type currentFunctionReturn = null;
			
			public Object visitFunction(Function func)
			{
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitFunction: " + func.getName());
				
				currentFunctionReturn = func.getReturnType();

				if(func.getSpecification() != null){

					Function parent = null;

					// check spec presence
					try{
						parent = this.symtab.lookupFn(func.getSpecification());
					}catch(UnrecognizedVariableException e){
						report(func, "Spec of "+ func.getName() + "not found");
						return super.visitFunction(func);
					}

					// check parameters
					Iterator formals1 = func.getParams().iterator();
					Iterator formals2 = parent.getParams().iterator();
					if(func.getParams().size() != parent.getParams().size() ){
						report(func, "Number of parameters of spec and sketch don't match " + parent + " vs.  " + func);
						return super.visitFunction(func);
					}
					while(formals1.hasNext()){
						Parameter f1 = (Parameter) formals1.next();
						Parameter f2 = (Parameter) formals2.next();
						if(! f1.getType().equals(f2.getType())){
							report(func, "Parameters of spec and sketch don't match: " + f1 + " vs. " + f2);
							return super.visitFunction(func);
						}
					}

					// check return value
					if (! func.getReturnType().promotesTo(parent.getReturnType())){
						report (func, "Return type of sketch & function are not compatible: " + func.getReturnType() + " vs. " + parent.getReturnType());
						return super.visitFunction(func);
					}
				}
				
				return super.visitFunction(func);
			}


			public Object visitExprFunCall(ExprFunCall exp)
			{                    
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitExprFunCall");

				Function fun = this.symtab.lookupFn(exp.getName());
				List formals = fun.getParams();
				Iterator form = formals.iterator();
				if(formals.size() != exp.getParams().size()){
					report(exp, "Wrong number of parameters");
					return super.visitExprFunCall(exp);
				}
				for (Iterator iter = exp.getParams().iterator(); iter.hasNext(); )
				{
					Expression param = (Expression)iter.next();
					Parameter formal = (Parameter) form.next();
					Type lt = getType(param);
					if(! lt.promotesTo(formal.getType())){
						report(exp, "Bad parameter type " + formal + "  " + fun);
					}
				}

				return super.visitExprFunCall(exp);
			}

			public Object visitExprBinary(ExprBinary expr)
			{
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitExprBinary");

				boolean isLeftArr = false;
				boolean isRightArr = false;
				Type lt = getType(expr.getLeft());
				Type rt = getType(expr.getRight());
				if(lt instanceof TypeArray){
					lt = ((TypeArray)lt).getBase();
					isLeftArr = true;
				}
				if(rt instanceof TypeArray){
					rt = ((TypeArray)rt).getBase();
					isRightArr=true;
				}
				if (lt != null && rt != null)
				{                        
					Type ct = lt.leastCommonPromotion(rt);
					if(expr.getOp() == ExprBinary.BINOP_LSHIFT || expr.getOp() == ExprBinary.BINOP_RSHIFT){
						ct = lt;
					}
					Type inttype =
						new TypePrimitive(TypePrimitive.TYPE_INT);
					Type bittype =
						new TypePrimitive(TypePrimitive.TYPE_BIT);
					Type cplxtype =
						new TypePrimitive(TypePrimitive.TYPE_COMPLEX);
					Type floattype =
						new TypePrimitive(TypePrimitive.TYPE_FLOAT);
					if (ct == null)
					{
						report (expr,
						"incompatible types in binary expression");
						return expr;
					}
					// Check whether ct is an appropriate type.
					switch (expr.getOp())
					{
					// Arithmetic operations:
					case ExprBinary.BINOP_ADD:
					case ExprBinary.BINOP_DIV:
					case ExprBinary.BINOP_MUL:
					case ExprBinary.BINOP_SUB:
						if (!(ct.promotesTo(cplxtype) || ct.promotesTo(TypePrimitive.inttype)))
							report(expr,
									"cannot perform arithmetic on " + ct);
						break;

						// Bitwise and integer operations:
					case ExprBinary.BINOP_BAND:
					case ExprBinary.BINOP_BOR:
					case ExprBinary.BINOP_BXOR:
						if (!ct.promotesTo(TypePrimitive.inttype))
							report(expr,
									"cannot perform bitwise operations on "
									+ ct);
						break;

					case ExprBinary.BINOP_MOD:
						if (!ct.promotesTo(TypePrimitive.inttype))
							report(expr, "cannot perform % on " + ct);
						break;

						// Boolean operations:
					case ExprBinary.BINOP_AND:
					case ExprBinary.BINOP_OR:
						if (!ct.promotesTo(TypePrimitive.bittype))
							report(expr,
									"cannot perform boolean operations on "
									+ ct);
						break;

						// Comparison operations:
					case ExprBinary.BINOP_GE:
					case ExprBinary.BINOP_GT:
					case ExprBinary.BINOP_LE:
					case ExprBinary.BINOP_LT:
						if (!ct.promotesTo(floattype) && !ct.promotesTo(TypePrimitive.inttype))
							report(expr,
									"cannot compare non-real type " + ct);
						if(isLeftArr || isRightArr )
							report(expr,
									"Comparissons are not supported for array types" + expr);
						break;

						// Equality, can compare anything:
					case ExprBinary.BINOP_EQ:
					case ExprBinary.BINOP_NEQ:
						break;
						// TODO: Make correct rule for SELECT.
					case ExprBinary.BINOP_SELECT:
						break;

					case ExprBinary.BINOP_LSHIFT:
					case ExprBinary.BINOP_RSHIFT:
						if (!isLeftArr && !(expr.getLeft() instanceof ExprConstInt))
							report(expr,
									"Can only shift array types for now. " + ct);
						break;
						// And now we should have covered everything.
					default:
						report(expr,
						"semantic checker missed a binop type");
					break;
					}
					//return expr;
				}

				return super.visitExprBinary(expr);
			}



			public Object visitExprTernary(ExprTernary expr)
			{
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitExprTernary");

				Type at = getType(expr.getA());
				Type bt = getType(expr.getB());
				Type ct = getType(expr.getC());

				if (at != null)
				{
					if (!at.promotesTo
							(TypePrimitive.inttype))
						report(expr,
								"first part of ternary expression "+
						"must be int");
				}

				if (bt != null && ct != null)
				{                        
					Type xt = bt.leastCommonPromotion(ct);
					if (xt == null)
						report(expr,
								"incompatible second and third types "+
						"in ternary expression");
				}

				return super.visitExprTernary(expr);
			}

			public Object visitExprField(ExprField expr)
			{
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitExprField");

				Type lt = getType(expr.getLeft());

				// Either lt is complex, or it's a structure
				// type, or it's null, or it's an error.
				if (lt == null)
				{
					// pass
				}
				else if (lt.isComplex())
				{
					String rn = expr.getName();
					if (!rn.equals("real") &&
							!rn.equals("imag"))
						report(expr,
								"complex variables have only "+
						"'real' and 'imag' fields");
				}
				else if (lt instanceof TypeStruct)
				{
					TypeStruct ts = (TypeStruct)lt;
					String rn = expr.getName();
					boolean found = false;
					for (int i = 0; i < ts.getNumFields(); i++)
						if (ts.getField(i).equals(rn))
						{
							found = true;
							break;
						}

					if (!found)
						report(expr,
								"structure does not have a field named "+
								"'" + rn + "'");
				}
				else
				{
					report(expr,
					"field reference of a non-structure type");
				}

				return super.visitExprField(expr);
			}

			public Object visitExprArrayRange(ExprArrayRange expr){
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitExprArrayRange");

				Type bt = getType(expr.getBase());
				if (bt != null)
				{
					if (!(bt instanceof TypeArray))
						report(expr, "array access with a non-array base");
				}else{
					report(expr, "array access with a non-array base");
				}
				List l=expr.getMembers();
				if(l.size() != 1){
					report(expr, "Ranges are not yet supported");
					return super.visitExprArrayRange(expr);
				}
				Object idx = l.get(0);
				if(!(idx instanceof RangeLen)){
					report(expr, "Ranges are not yet supported");
					return super.visitExprArrayRange(expr);
				}
				RangeLen rl = (RangeLen)idx;                	
				Type ot = getType(rl.start());
				if (ot != null)
				{
					if (!ot.promotesTo
							(new TypePrimitive(TypePrimitive.TYPE_INT)))
						report(expr, "array index must be an int");
				}else{
					report(expr, "array index must be an int");
				}
				return super.visitExprArrayRange(expr);
			}

			/*			public Object visitExprArray(ExprArray expr)
			{
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitExprArray");

				Type bt = getType(expr.getBase());
				Type ot = getType(expr.getOffset());

				if (bt != null)
				{
					if (!(bt instanceof TypeArray))
						report(expr, "array access with a non-array base");
				}

				if (ot != null)
				{
					if (!ot.promotesTo
							(new TypePrimitive(TypePrimitive.TYPE_NDINT)))
						report(expr, "array index must be an int, but is a " + ot);
				}

				return super.visitExprArray(expr);
			}*/

			public Object visitExprArrayInit(ExprArrayInit expr)
			{
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitExprArrayInit");

				// check for uniform length and dimensions among all children.
				List elems = expr.getElements();

				// only worry about it if we have elements
				if (elems.size()>0) {
					Expression first = (Expression)elems.get(0);
					// if one is an array, they should all be
					// arrays of the same length and dimensions
					if (first instanceof ExprArrayInit) {
						ExprArrayInit firstArr = (ExprArrayInit)first;
						for (int i=1; i<elems.size(); i++) {
							ExprArrayInit other = (ExprArrayInit)elems.get(i);
							if (firstArr.getDims() != other.getDims()) {
								report(expr, 
										"non-uniform number of array " +
								"dimensions in array initializer");
							}
							if (firstArr.getElements().size() != other.getElements().size()) {
								report(expr, 
										"two rows of a multi-dimensional " +  
										"array are initialized to different " + 
								"lengths (arrays must be rectangular)");
							}
						}
					} else {
						// if first element is not array, no other
						// element should be an array
						for (int i=1; i<elems.size(); i++) {
							if (elems.get(i) instanceof ExprArrayInit) {
								report(expr, 
										"non-uniform number of array " +
								"dimensions in array initializer");
							}
						}
					}
				}

				return super.visitExprArrayInit(expr);
			}

			public Object visitFieldDecl(FieldDecl field) {
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitFieldDecl");

				// check that array sizes match
				for (int i=0; i<field.getNumFields(); i++) {
					Type type = field.getType(i);
					Expression init = field.getInit(i);
					if (type instanceof TypeArray && init!=null) {
						// check that initializer is array initializer
						// (I guess it could also be conditional expression?  Don't bother.)
						if (!(init instanceof ExprArrayInit)) {
							report (field, "array initialized to non-array type");
						} else {
							// check that lengths match
							Expression lengthExpr = ((TypeArray)type).getLength();
							// only check it if we have resolved it
							if (lengthExpr instanceof ExprConstInt) {
								int length = ((ExprConstInt)lengthExpr).getVal();
								if (length != ((ExprArrayInit)init).getElements().size()) {
									report(field, 
											"declared array length does not match " +
									"array initializer");
								}
							}
						}
					}
				}

				return super.visitFieldDecl(field);
			}

			public void matchTypes(Statement stmt,String lhsn, Type lt, Type rt){
				


				if (lt != null && rt != null &&
						!(rt.promotesTo(lt)))
					report(stmt,
							"right-hand side of assignment must "+
					"be promotable to left-hand side's type " + lt + "!>=" + rt );
				if( lt == null || rt == null)
					report(stmt,
					"This assignments involves a bad type");
			}
			public Object visitStmtAssign(StmtAssign stmt)
			{
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitStmtAssign");

				Type lt = getType(stmt.getLHS());
				Type rt = getType(stmt.getRHS());
				String lhsn = null;
				Expression lhsExp = stmt.getLHS();
				if(lhsExp instanceof ExprArray){
					lhsExp = ((ExprArray)stmt.getLHS()).getBase();
				}
				if(lhsExp instanceof ExprArrayRange){
					lhsExp = ((ExprArrayRange)stmt.getLHS()).getBase();
				}
				if(lhsExp instanceof ExprVar){
					lhsn = ( (ExprVar) lhsExp).getName();
				}                    
				matchTypes(stmt, lhsn, lt, rt);
				return super.visitStmtAssign(stmt);
			}

			public Object visitStmtVarDecl(StmtVarDecl stmt)
			{
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitStmtVarDecl");

				Object result = super.visitStmtVarDecl(stmt); 
				for (int i = 0; i < stmt.getNumVars(); i++){
					Expression ie = stmt.getInit(i);
					if(ie != null){
						Type rt = getType(ie);

						matchTypes(stmt, stmt.getName(i), actualType(stmt.getType(i)), rt);
					}
				}
				return result;
			}

			public Object visitStmtAssert(StmtAssert stmt)
			{
				Object result = super.visitStmtAssert(stmt);

				// check that the associated condition is promotable to a boolean
				Type ct = getType(stmt.getCond());
				Type bt = new TypePrimitive(TypePrimitive.TYPE_BIT);

				if (!ct.promotesTo(bt))
					report (stmt, "assert must be passed a boolean");

				return result;
			}

			// Control Statements
			
			public Object visitStmtDoWhile(StmtDoWhile stmt)
			{
				// check the condition
				Type cond = getType(stmt.getCond());
				if (!cond.promotesTo(new TypePrimitive(TypePrimitive.TYPE_BIT)))
					report (stmt, "Condition clause is not a promotable to a bit");

				// should really also check whether any variables are modified in the loop body

				return super.visitStmtDoWhile(stmt);
			}

			public Object visitStmtFor(StmtFor stmt)
			{
				// check the condition
				
				if( stmt.getInit() == null){
					report(stmt, "For loops without initializer not supported." );
				}
				
				Type cond = getType(stmt.getCond());
				if (!cond.promotesTo(new TypePrimitive(TypePrimitive.TYPE_BIT)))
					report (stmt, "Condition clause is not a proper conditional");

				// also need to check that the variable is incremented by 1
				StmtExpr incr = (StmtExpr)stmt.getIncr();
				ExprUnary incrExpr = (ExprUnary) incr.getExpression();	

				if (incrExpr.getOp() != ExprUnary.UNOP_POSTINC && 
						incrExpr.getOp() != ExprUnary.UNOP_POSTDEC &&
						incrExpr.getOp() != ExprUnary.UNOP_PREINC && 
						incrExpr.getOp() != ExprUnary.UNOP_PREDEC )
					report (stmt, "Increment expression should be of the form (var){++,--}: " + incrExpr);

				// again, should check for variable usage

				return super.visitStmtFor(stmt);
			}

			public Object visitStmtIfThen(StmtIfThen stmt)
			{
				// check the condition
				Type cond = getType(stmt.getCond());
				if (!cond.promotesTo(new TypePrimitive(TypePrimitive.TYPE_BIT)))
					report (stmt, "Condition clause is not a proper conditional");

				return super.visitStmtIfThen(stmt);
			}

			public Object visitStmtLoop(StmtLoop stmt)
			{
				// variable in loop should promote to an int
				Type cond = getType(stmt.getIter());
				if (!cond.promotesTo(new TypePrimitive(TypePrimitive.TYPE_INT)))
					report (stmt, "Iteration count is not convertable to an integer");

				return super.visitStmtLoop(stmt);
			}

			public Object visitStmtWhile(StmtWhile stmt)
			{
				// check the condition
				Type cond = getType(stmt.getCond());
				if (!cond.promotesTo(new TypePrimitive(TypePrimitive.TYPE_BIT)))
					report (stmt, "Condition clause is not a proper conditional");

				return super.visitStmtWhile(stmt);
			}
			
			public Object visitStmtReturn(StmtReturn stmt)
			{
				// Check that the return value can be promoted to the
				// function return type
				//System.out.println("checkBasicTyping::SymbolTableVisitor::visitStmtReturn");
				//System.out.println("Return values: " + currentFunctionReturn + " vs. " + getType(stmt.getValue()));
				
				if (! getType(stmt.getValue()).promotesTo(currentFunctionReturn))
					report (stmt, "Return value incompatible with declared function return value: " + currentFunctionReturn + " vs. " + getType(stmt.getValue()));
				
				return super.visitStmtReturn(stmt);
			}
			
		});
	}

	/**
	 * Checks that statements that must be invoked some number
	 * of times in fact are.  This includes checking that split-join
	 * and feedback loop init functions have exactly one splitter
	 * and exactly one joiner.
	 *
	 * @param prog  parsed program object to check
	 */
	public void checkStatementCounts(Program prog)
	{
		// Look for init functions in split-joins and feedback loops:
		prog.accept(new FEReplacer() {
			public Object visitStreamSpec(StreamSpec ss)
			{
				if (ss.getType() == StreamSpec.STREAM_SPLITJOIN ||
						ss.getType() == StreamSpec.STREAM_FEEDBACKLOOP)
				{
					exactlyOneStatement
					(ss, "split",
							new StatementCounter() {
						public boolean
						statementQualifies(Statement stmt)
						{ return stmt instanceof StmtSplit; }
					});
					exactlyOneStatement
					(ss, "join",
							new StatementCounter() {
						public boolean
						statementQualifies(Statement stmt)
						{ return stmt instanceof StmtJoin; }
					});
				}

				if (ss.getType() == StreamSpec.STREAM_FEEDBACKLOOP)
				{
					exactlyOneStatement
					(ss, "body",
							new StatementCounter() {
						public boolean
						statementQualifies(Statement stmt)
						{ return stmt instanceof StmtBody; }
					});
					exactlyOneStatement
					(ss, "loop",
							new StatementCounter() {
						public boolean
						statementQualifies(Statement stmt)
						{ return stmt instanceof StmtLoop; }
					});
				}
				return super.visitStreamSpec(ss);
			}
		});
	}

	private void exactlyOneStatement(StreamSpec ss, String stype,
			StatementCounter sc)
	{
		Function init = ss.getInitFunc();
		assert init != null;
		CFG cfg = CFGBuilder.buildCFG(init);
		Map splitCounts = sc.run(cfg);
		// TODO: modularize this analysis; report the first place
		// where there's a second split/join, and/or the first place
		// where there's ambiguity (bottom).  This would be easier if
		// Java had lambdas.
		CountLattice exitVal = (CountLattice)splitCounts.get(cfg.getExit());
		if (exitVal.isTop())
			report(init, "weird failure: " + stype + " exit value is top");
		else if (exitVal.isBottom())
			report(init, "couldn't determine number of " + stype +
			" statements");
		else if (exitVal.getValue() == 0)
			report(init, "no " + stype + " statements");
		else if (exitVal.getValue() > 1)
			report(init, "more than one " + stype + " statement");
	}

	/**
	 * Check that variables are declared and used correctly.  In
	 * particular, check that variables are declared before their
	 * first use, that local variables and fields don't shadow stream
	 * parameters, and that stream parameters don't appear on the
	 * left-hand side of assignment statements or inside mutating
	 * unary operations.
	 *
	 * @param prog  parsed program object to check
	 */
	public void checkVariableUsage(Program prog)
	{
		prog.accept(new SymbolTableVisitor(null) {
			public Object visitExprVar(ExprVar var)
			{
				// Check: the variable is declared somewhere.
				try
				{
					symtab.lookupVar(var);
				}
				catch(UnrecognizedVariableException e)
				{
					report(var, "unrecognized variable '" + var.getName() + "'");
				}
				return super.visitExprVar(var);
			}

			private boolean isStreamParam(String name)
			{
				try
				{
					int kind = symtab.lookupKind(name);
					if (kind == SymbolTable.KIND_STREAM_PARAM)
						return true;
				}
				catch(UnrecognizedVariableException e)
				{
					// ignore; calling code should have recursive
					// calls which will catch this
				}
				return false;
			}

			public Object visitStmtVarDecl(StmtVarDecl stmt)
			{
				// Check: none of the locals shadow stream parameters.
				for (int i = 0; i < stmt.getNumVars(); i++)
				{
					String name = stmt.getName(i);
					if (isStreamParam(name))
						report(stmt,
						"local variable shadows stream parameter");
				}
				return super.visitStmtVarDecl(stmt);
			}

			public Object visitStmtAssign(StmtAssign stmt)
			{
				// Check: LHS isn't a stream parameter.
				Expression lhs = stmt.getLHS();
				if (lhs instanceof ExprVar)
				{
					ExprVar lhsv = (ExprVar)lhs;
					String name = lhsv.getName();
					if (isStreamParam(name))
						report(stmt, "assignment to stream parameter");
				}
				return super.visitStmtAssign(stmt);
			}

			public Object visitExprUnary(ExprUnary expr)
			{
				int op = expr.getOp();
				Expression child = expr.getExpr();
				if ((child instanceof ExprVar) &&
						(op == ExprUnary.UNOP_PREINC ||
								op == ExprUnary.UNOP_POSTINC ||
								op == ExprUnary.UNOP_PREDEC ||
								op == ExprUnary.UNOP_POSTDEC))
				{
					ExprVar var = (ExprVar)child;
					String name = var.getName();
					if (isStreamParam(name))
						report(expr, "modification of stream parameter");
				}
				return super.visitExprUnary(expr);
			}
		});
	}
}

