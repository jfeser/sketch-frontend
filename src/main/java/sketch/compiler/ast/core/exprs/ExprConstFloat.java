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

package sketch.compiler.ast.core.exprs;
import sketch.compiler.ast.core.FEContext;
import sketch.compiler.ast.core.FENode;
import sketch.compiler.ast.core.FEVisitor;

/**
 * A real-valued constant.  This can appear in an ExprComplex to form
 * a complex real expression.
 *
 * @author  David Maze &lt;dmaze@cag.lcs.mit.edu&gt;
 * @version $Id$
 */
public class ExprConstFloat extends ExprConstant
{
	public static final ExprConstFloat ZERO = new ExprConstFloat ((FEContext)null, 0.0);

    private double val;

    /**
     * Create a new ExprConstFloat with a specified value.
     *
     * @param context  file and line number for the expression
     * @param val      value of the constant
     */
    public ExprConstFloat(FENode context, double val)
    {
        super(context);
        this.val = val;
    }

    /**
     * Create a new ExprConstFloat with a specified value.
     *
     * @param context  file and line number for the expression
     * @param val      value of the constant
     * @deprecated
     */
    public ExprConstFloat(FEContext context, double val)
    {
        super(context);
        this.val = val;
    }

    /**
     * Create a new ExprConstFloat with a specified value but no context.
     *
     * @param val  value of the constant
     */
    public ExprConstFloat(double val)
    {
        this((FEContext) null, val);
    }

    /**
     * Parse a string as a double, and create a new ExprConstFloat
     * from the result.
     *
     * @param context  file and line number for the expression
     * @param str      string representing the value of the constant
     */
    public ExprConstFloat(FENode context, String str)
    {
        this(context, Double.parseDouble(str));
    }

    /**
     * Returns the value of this.
     *
     * @return float value of the constant
     */
    public double getVal() { return val; }

    /** Accept a front-end visitor. */
    public Object accept(FEVisitor v)
    {
        return v.visitExprConstFloat(this);
    }

    public String toString(){
    	return Double.toString(getVal());
    }
}
