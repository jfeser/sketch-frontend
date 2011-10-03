package sketch.compiler.ast.spmd.exprs;
import sketch.compiler.ast.core.FEContext;
import sketch.compiler.ast.core.FEVisitor;
import sketch.compiler.ast.core.exprs.Expression;

public class SpmdPid extends Expression {
    @SuppressWarnings("deprecation")
    public SpmdPid(FEContext context) {
        super(context);
    }

    @Override
    public Object accept(FEVisitor v) {
        return v.visitSpmdPid(this);
    }

    @Override
    public String toString() {
        return "spmdpid";
    }
}
