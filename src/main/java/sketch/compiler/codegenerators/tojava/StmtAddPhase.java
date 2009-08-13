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

package sketch.compiler.codegenerators.tojava;

import sketch.compiler.ast.core.FENode;
import sketch.compiler.ast.core.FEVisitor;
import sketch.compiler.ast.core.FuncWork;
import sketch.compiler.ast.core.exprs.Expression;
import sketch.compiler.ast.core.stmts.Statement;

/**
 * A new-old-syntax statement to add an initialization or steady-state
 * phase.  This corresponds to either a <code>addInitPhase</code> or
 * an <code>addSteadyPhase</code> call for the new-and-improved phased
 * filter format.  The call has peek, pop, and push rates and the name
 * of a method to call.
 *
 * @author  David Maze &lt;dmaze@cag.lcs.mit.edu&gt;
 * @version $Id$
 */
public class StmtAddPhase extends Statement
{
    private boolean init;
    private Expression peek, pop, push;
    private String name;

    /**
     * Create a phase-adding statement from explicit I/O rates and a function
     * name.  A null peek rate implies the same rate as the pop rate;
     * a null pop or push rate implies a rate of 0.
     *
     * @param context  Context this statement appears in
     * @param init     true if the phase is an init phase
     * @param peek     Peek rate of the phase, or null
     * @param pop      Pop rate of the phase, or null
     * @param push     Push rate of the phase, or null
     * @param name     Name of the phase function
     */
    public StmtAddPhase(FENode context, boolean init,
                        Expression peek, Expression pop,
                        Expression push, String name)
    {
        super(context);
        this.init = init;
        this.peek = peek;
        this.pop = pop;
        this.push = push;
        this.name = name;
    }

    /**
     * Create a phase-adding statement from a work function.
     *
     * @param context  Context this statement appears in
     * @param init     true if the phase is an init phase
     * @param work     Work function constituting the phase
     */
    public StmtAddPhase(FENode context, boolean init, FuncWork work)
    {
        super(context);
        this.init = init;
        this.peek = work.getPeekRate();
        this.pop = work.getPopRate();
        this.push = work.getPushRate();
        if (work.getName() != null)
            this.name = work.getName();
        else if (init)
            this.name = "prework";
        else
            this.name = "work";
    }

    /** Returns true if the phase is an init phase, false if the phase
     * is a steady-state phase. */
    public boolean isInit()
    {
        return init;
    }

    /** Returns the peek rate of the phase. */
    public Expression getPeek()
    {
        return peek;
    }

    /** Returns the pop rate of the phase. */
    public Expression getPop()
    {
        return pop;
    }

    /** Returns the push rate of the phase. */
    public Expression getPush()
    {
        return push;
    }

    /** Returns the name of the phase function. */
    public String getName()
    {
        return name;
    }

    public Object accept(FEVisitor v)
    {
        return v.visitOther(this);
    }
}
