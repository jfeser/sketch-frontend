/**
 *
 */
package sketch.compiler.passes.lowering;
import java.util.ArrayList;
import java.util.List;

import sketch.compiler.ast.core.FENode;
import sketch.compiler.ast.core.exprs.ExprArrayRange;
import sketch.compiler.ast.core.exprs.ExprBinary;
import sketch.compiler.ast.core.exprs.ExprConstInt;
import sketch.compiler.ast.core.exprs.ExprConstant;
import sketch.compiler.ast.core.exprs.Expression;
import sketch.compiler.ast.core.exprs.ExprArrayRange.RangeLen;
import sketch.compiler.ast.core.typs.TypeArray;

/**
 * Rewrites array types and array accesses so that we are left with
 * one-dimensional arrays only.  E.g.:
 * <code>
 * bit[2][4] x = 0;
 * bit y = x[1][2];
 *
 * // is rewritten to
 *
 * bit[2*4] x = 0;
 * bit y = x[1*4 + 2];
 * </code>
 *
 * @author Chris Jones
 *
 */
public class EliminateMultiDimArrays extends SymbolTableVisitor {
	public EliminateMultiDimArrays () {
		super (null);
	}

	/** Flatten multi-dimensional array types */
	public Object visitTypeArray (TypeArray t) {
		TypeArray ta = (TypeArray) super.visitTypeArray (t);

		if (ta.getBase ().isArray ()) {
			TypeArray base = (TypeArray) ta.getBase ();
			List<Expression> dims = base.getDimensions ();

			dims.add (ta.getLength ());
			return new TypeArray (base.getBase (),
				new ExprBinary (base.getLength (),
								ExprBinary.BINOP_MUL,
								base.getLength (),
								ta.getLength ()),
								dims);
		} else {
			return ta;
		}
	}

	public Object visitExprArrayRange (ExprArrayRange ear) {
		// Do the recursive rewrite
		Expression base = 			doExpression (ear.getAbsoluteBaseExpr ());
		List<RangeLen> oldIndices = ear.getArraySelections ();
		List<RangeLen> indices =	new ArrayList<RangeLen> ();

		for (RangeLen rl : oldIndices) {
			Expression newStart = doExpression (rl.start ());

			if(newStart != rl.start ())
				rl = new RangeLen (newStart, rl.len ());
			indices.add(rl);
		}

		List<Expression> dims =
			((TypeArray) getType (ear.getAbsoluteBaseExpr ())).getDimensions ();
		if (1 == dims.size ()) {
			return new ExprArrayRange (ear, base, indices.get (0));
		}

		// And then flatten indexes involving multi-dim arrays
		if (false == isSupportedIndex (indices)) {
			ear.report ("sorry, slices like x[0::2][2] are not supported");
			throw new RuntimeException ();
		}

		Expression idx = ExprConstant.createConstant (ear, "0");
		for (RangeLen rl : indices) {
			dims.remove (dims.size ()-1);
			idx = new ExprBinary (idx, ExprBinary.BINOP_ADD,
					idx,
					new ExprBinary (idx,
									ExprBinary.BINOP_MUL,
					 	  		    product (dims, idx),
					 	  		    rl.start ()));
		}

		Expression size =
			new ExprBinary (idx, ExprBinary.BINOP_MUL,
					new ExprConstInt (idx, indices.get (indices.size ()-1).len ()),
					product (dims, idx));
		RangeLen flatRl = new RangeLen (idx, size);

		return new ExprArrayRange (ear, base, flatRl);
	}

	/**
	 * Return an Expression representing the product of the elements in EXPRS.
	 *
	 * TODO: refactor to somewhere else
	 */
	private Expression product (List<Expression> exprs, FENode cx) {
		Expression prod = ExprConstant.createConstant (cx, "1");
		for (Expression e : exprs)
			prod = new ExprBinary (prod, ExprBinary.BINOP_MUL, prod, e);
		return prod;
	}

	/**
	 * Currently, we only support multi-dimensional indexes of the following
	 * forms:
	 *
	 * T[1][2]... arr;  arr[0];
	 * T[1]...[N] arr;  arr[0][0];
	 * T[1]...[N] arr;  arr[0][0::2];
	 *
	 * This function returns true iff INDICES represent one of those forms.
	 */
	private boolean isSupportedIndex (List<RangeLen> indices) {
		for (int i = 0; i < (indices.size () - 1); ++i)
			if (indices.get (i).len () != 1)
				return false;
		return true;
	}
}
