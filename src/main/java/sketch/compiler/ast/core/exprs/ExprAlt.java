/**
 *
 */
package sketch.compiler.ast.core.exprs;
import sketch.compiler.ast.core.FENode;
import sketch.compiler.ast.core.FEVisitor;

/**
 * @author <a href="mailto:cgjones@cs.berkeley.edu">Chris Jones</a>
 *
 */
public class ExprAlt extends Expression {
	Expression ths;
	Expression that;

	public ExprAlt (Expression ths, Expression that) {
		this (ths, ths, that);
	}

	public ExprAlt (FENode cx, Expression ths, Expression that) {
		super (cx);
		this.ths = ths;
		this.that = that;
	}

	public Expression getThis () { return ths; }
	public Expression getThat () { return that; }

	@Override public boolean isLValue () {
		return ths.isLValue () && that.isLValue ();
	}

	public String toString () {
		return "("+ ths +" | "+ that +")";
	}

	@Override
	public Object accept (FEVisitor v) {
		return v.visitExprAlt (this);
	}

}
