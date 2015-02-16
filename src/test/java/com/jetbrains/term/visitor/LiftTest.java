package test.java.com.jetbrains.term.visitor;

import main.java.com.jetbrains.term.expr.*;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LiftTest {
    @Test
    public void liftConst() {
        // lift( null -> zero , 0, 1) = null -> zero
        Expression expr1 = new ZeroExpression();
        Expression expr2 = new DefCallExpression(null);
        Expression expr3 = new PiExpression(expr1, expr2);
        assertEquals(expr3, expr3.liftIndex(0, 1));
    }

    @Test
    public void liftIndexLess() {
        // lift( var(2) , 4, 3) = var(2)
        Expression expr = new IndexExpression(2);
        assertEquals(expr, expr.liftIndex(4, 3));
    }

    @Test
    public void liftIndexGreater() throws Exception {
        // lift( var(2) , 1, 3) = var(5)
        Expression expr = new IndexExpression(2);
        assertEquals(new IndexExpression(5), expr.liftIndex(1, 3));
    }

    @Test
    public void liftLambdaClosed() throws Exception {
        // lift( \x.x , 0, 1) = \x.x
        Expression expr = new LamExpression("x", new IndexExpression(0));
        assertEquals(expr, expr.liftIndex(0, 1));
    }

    @Test
    public void liftLambdaOpen() throws Exception {
        // lift( \x.var(1) , 1, 2) = \x.var(3)
        Expression expr = new LamExpression("x", new IndexExpression(2));
        assertEquals(new LamExpression("x", new IndexExpression(4)), expr.liftIndex(1, 2));
    }

    @Test
    public void liftLambda2() throws Exception {
        // lift( (\x. x) (\y. var(0)) , 0, 2) = (\x. x) (\z. var(2))
        Expression expr1 = new LamExpression("x", new IndexExpression(0));
        Expression expr2 = new LamExpression("y", new IndexExpression(1));
        Expression expr3 = new AppExpression(expr1, expr2);
        assertEquals(new AppExpression(expr1, new LamExpression("z", new IndexExpression(3))), expr3.liftIndex(0, 2));
    }

    @Test
    public void liftComplex() throws Exception {
        // lift( (\x y. x y x) (\x y. x (var(1)) (var(0)) (\z. z (var(0)) (var(2)))) ), 1, 2) = (\x y. x y x) (\x y. x (var(3)) (var(0)) (\z. z (var(0)) (var(4))))
        Expression expr1 = new LamExpression("x", new LamExpression("y", new AppExpression(new AppExpression(new IndexExpression(1), new IndexExpression(0)), new IndexExpression(1))));
        Expression expr2 = new LamExpression("x", new LamExpression("y", new AppExpression(new AppExpression(new AppExpression(new IndexExpression(1), new IndexExpression(3)), new IndexExpression(2)), new LamExpression("z", new AppExpression(new AppExpression(new IndexExpression(0), new IndexExpression(3)), new IndexExpression(5))))));
        Expression expr3 = new LamExpression("x", new LamExpression("y", new AppExpression(new AppExpression(new AppExpression(new IndexExpression(1), new IndexExpression(5)), new IndexExpression(2)), new LamExpression("z", new AppExpression(new AppExpression(new IndexExpression(0), new IndexExpression(3)), new IndexExpression(7))))));
        assertEquals(new AppExpression(expr1, expr3), (new AppExpression(expr1, expr2)).liftIndex(1, 2));
    }

    @Test
    public void liftPiClosed() throws Exception {
        // lift ( (x : N) -> N x, 0, 1) = (x : N) -> N x
        Expression expr = new PiExpression("x", new NatExpression(), new AppExpression(new NatExpression(), new IndexExpression(0)));
        assertEquals(expr, expr.liftIndex(0, 1));
    }

    @Test
    public void liftPiOpen() throws Exception {
        // lift ( (x : N) -> N (var(0)), 0, 1) = (x : N) -> N (var(1))
        Expression expr1 = new PiExpression("x", new NatExpression(), new AppExpression(new NatExpression(), new IndexExpression(1)));
        Expression expr2 = new PiExpression("y", new NatExpression(), new AppExpression(new NatExpression(), new IndexExpression(2)));
        assertEquals(expr2, expr1.liftIndex(0, 1));
    }

    @Test
    public void liftArr() throws Exception {
        // lift ( N -> N (var(0)), 0, 1) = N -> N (var(1))
        Expression expr1 = new PiExpression(new NatExpression(), new AppExpression(new NatExpression(), new IndexExpression(0)));
        Expression expr2 = new PiExpression(new NatExpression(), new AppExpression(new NatExpression(), new IndexExpression(1)));
        assertEquals(expr2, expr1.liftIndex(0, 1));
    }
}
