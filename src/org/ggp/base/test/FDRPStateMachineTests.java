
package org.ggp.base.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.ggp.base.util.game.CloudGameRepository;
import org.ggp.base.util.game.TestGameRepository;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;


public class FDRPStateMachineTests extends Assert
{
  protected final StateMachine mStateMachine = new ForwardDeadReckonPropnetStateMachine();

  protected final GdlConstant  C1   = GdlPool.getConstant("1");
  protected final GdlConstant  C2   = GdlPool.getConstant("2");
  protected final GdlConstant  C3   = GdlPool.getConstant("3");
  protected final GdlConstant  C50  = GdlPool.getConstant("50");
  protected final GdlConstant  C100 = GdlPool.getConstant("100");

  @Test
  public void testProverOnTicTacToe() throws Exception
  {
    List<Gdl> ticTacToeDesc = new TestGameRepository().getGame("ticTacToe").getRules();
    mStateMachine.initialize(ticTacToeDesc);
    MachineState state = mStateMachine.getInitialState();
    assertFalse(mStateMachine.isTerminal(state));
    GdlConstant X_PLAYER = GdlPool.getConstant("xplayer");
    GdlConstant O_PLAYER = GdlPool.getConstant("oplayer");
    Role xRole = new Role(X_PLAYER);
    Role oRole = new Role(O_PLAYER);
    assertTrue(Arrays.equals(mStateMachine.getRoles(), new Role[] {xRole, oRole}));

    assertEquals(9, mStateMachine.getLegalJointMoves(state).size());
    assertEquals(9, mStateMachine.getLegalMoves(state, xRole).size());
    assertEquals(1, mStateMachine.getLegalMoves(state, oRole).size());
    Move noop = new Move(GdlPool.getConstant("noop"));
    assertEquals(noop, mStateMachine.getLegalMoves(state, oRole).get(0));

    Move m11 = move("mark 1 1");
    assertTrue(mStateMachine.getLegalMoves(state, xRole).contains(m11));
    state = mStateMachine.getNextState(state, Arrays.asList(new Move[] {m11, noop}));
    assertFalse(mStateMachine.isTerminal(state));

    Move m13 = move("mark 1 3");
    assertTrue(mStateMachine.getLegalMoves(state, oRole).contains(m13));
    state = mStateMachine.getNextState(state, Arrays.asList(new Move[] {noop, m13}));
    assertFalse(mStateMachine.isTerminal(state));

    Move m31 = move("mark 3 1");
    assertTrue(mStateMachine.getLegalMoves(state, xRole).contains(m31));
    state = mStateMachine.getNextState(state, Arrays.asList(new Move[] {m31, noop}));
    assertFalse(mStateMachine.isTerminal(state));

    Move m22 = move("mark 2 2");
    assertTrue(mStateMachine.getLegalMoves(state, oRole).contains(m22));
    state = mStateMachine.getNextState(state, Arrays.asList(new Move[] {noop, m22}));
    assertFalse(mStateMachine.isTerminal(state));

    Move m21 = move("mark 2 1");
    assertTrue(mStateMachine.getLegalMoves(state, xRole).contains(m21));
    state = mStateMachine.getNextState(state, Arrays.asList(new Move[] {m21, noop}));
    assertTrue(mStateMachine.isTerminal(state));
    assertEquals(100, mStateMachine.getGoal(state, xRole));
    assertEquals(0, mStateMachine.getGoal(state, oRole));
    assertEquals(Arrays.asList(new Integer[] {100, 0}), mStateMachine.getGoals(state));
  }

  @Test
  public void testCase1A() throws Exception
  {
    List<Gdl> desc = new TestGameRepository().getGame("test_case_1a").getRules();
    mStateMachine.initialize(desc);
    MachineState state = mStateMachine.getInitialState();
    Role you = new Role(GdlPool.getConstant("you"));
    assertFalse(mStateMachine.isTerminal(state));
    assertEquals(100, mStateMachine.getGoal(state, you));
    assertEquals(Collections.singletonList(100), mStateMachine.getGoals(state));
    state = mStateMachine.getNextState(state, Collections.singletonList(move("proceed")));
    assertTrue(mStateMachine.isTerminal(state));
    assertEquals(100, mStateMachine.getGoal(state, you));
    assertEquals(Collections.singletonList(100), mStateMachine.getGoals(state));
  }

  @Test
  public void testCase3C() throws Exception
  {
    List<Gdl> desc = new TestGameRepository().getGame("test_case_3c").getRules();
    mStateMachine.initialize(desc);
    MachineState state = mStateMachine.getInitialState();
    Role xplayer = new Role(GdlPool.getConstant("xplayer"));
    assertFalse(mStateMachine.isTerminal(state));
    assertEquals(1, mStateMachine.getLegalMoves(state, xplayer).size());
    assertEquals(move("win"), mStateMachine.getLegalMoves(state, xplayer).get(0));
    state = mStateMachine.getNextState(state, Collections.singletonList(move("win")));
    assertTrue(mStateMachine.isTerminal(state));
    assertEquals(100, mStateMachine.getGoal(state, xplayer));
    assertEquals(Collections.singletonList(100), mStateMachine.getGoals(state));
  }

  @Ignore("Works for ProverStateMachine but causes stack overflow with FDRPSM.  See #197.")
  @Test
  public void testCase5A() throws Exception
  {
    List<Gdl> desc = new TestGameRepository().getGame("test_case_5a").getRules();
    mStateMachine.initialize(desc);
    MachineState state = mStateMachine.getInitialState();
    Role you = new Role(GdlPool.getConstant("you"));
    assertFalse(mStateMachine.isTerminal(state));
    assertEquals(1, mStateMachine.getLegalMoves(state, you).size());
    assertEquals(move("proceed"), mStateMachine.getLegalMoves(state, you).get(0));
    state = mStateMachine.getNextState(state, Collections.singletonList(move("proceed")));
    assertTrue(mStateMachine.isTerminal(state));
    assertEquals(100, mStateMachine.getGoal(state, you));
    assertEquals(Collections.singletonList(100), mStateMachine.getGoals(state));
  }

  @Test
  public void testCase5B() throws Exception
  {
    List<Gdl> desc = new TestGameRepository().getGame("test_case_5b").getRules();
    mStateMachine.initialize(desc);
    MachineState state = mStateMachine.getInitialState();
    Role you = new Role(GdlPool.getConstant("you"));
    assertFalse(mStateMachine.isTerminal(state));
    assertEquals(1, mStateMachine.getLegalMoves(state, you).size());
    assertEquals(move("draw 1 1 1 2"), mStateMachine.getLegalMoves(state, you).get(0));
    state = mStateMachine.getNextState(state, Collections.singletonList(move("draw 1 1 1 2")));
    assertTrue(mStateMachine.isTerminal(state));
  }

  @Test
  public void testCase5C() throws Exception
  {
    List<Gdl> desc = new TestGameRepository().getGame("test_case_5c").getRules();
    mStateMachine.initialize(desc);
    MachineState state = mStateMachine.getInitialState();
    Role you = new Role(GdlPool.getConstant("you"));
    assertFalse(mStateMachine.isTerminal(state));
    assertEquals(1, mStateMachine.getLegalMoves(state, you).size());
    assertEquals(move("proceed"), mStateMachine.getLegalMoves(state, you).get(0));
    state = mStateMachine.getNextState(state, Collections.singletonList(move("proceed")));
    assertTrue(mStateMachine.isTerminal(state));
    assertEquals(100, mStateMachine.getGoal(state, you));
    assertEquals(Collections.singletonList(100), mStateMachine.getGoals(state));
  }

  @Ignore("Works for ProverStateMachine but hangs with FDRPSM.  See #212.")
  @Test
  public void testCase5D() throws Exception
  {
    List<Gdl> desc = new TestGameRepository().getGame("test_case_5d").getRules();
    mStateMachine.initialize(desc);
    MachineState state = mStateMachine.getInitialState();
    Role you = new Role(GdlPool.getConstant("you"));
    assertFalse(mStateMachine.isTerminal(state));
    assertEquals(1, mStateMachine.getLegalMoves(state, you).size());
    assertEquals(move("proceed"), mStateMachine.getLegalMoves(state, you).get(0));
    state = mStateMachine.getNextState(state, Collections.singletonList(move("proceed")));
    assertTrue(mStateMachine.isTerminal(state));
    assertEquals(100, mStateMachine.getGoal(state, you));
    assertEquals(Collections.singletonList(100), mStateMachine.getGoals(state));
  }

  @Test
  public void testDistinctAtBeginningOfRule() throws Exception
  {
    List<Gdl> desc = new TestGameRepository().getGame("test_distinct_beginning_rule").getRules();
    mStateMachine.initialize(desc);
    MachineState state = mStateMachine.getInitialState();
    Role you = new Role(GdlPool.getConstant("you"));
    assertFalse(mStateMachine.isTerminal(state));
    assertEquals(2, mStateMachine.getLegalMoves(state, you).size());
    state = mStateMachine.getNextState(state, Collections.singletonList(move("do a b")));
    assertTrue(mStateMachine.isTerminal(state));
    assertEquals(100, mStateMachine.getGoal(state, you));
    assertEquals(Collections.singletonList(100), mStateMachine.getGoals(state));
  }

  private class PropNetSize
  {
    public final String mRepo;
    public final String mGame;
    public final int mFullSize;
    public final int mXSize;
    public final int mOSize;
    public final int mGoalSize;

    public PropNetSize(String xiRepo, String xiGame,
                       int xiFullSize, int xiXSize, int xiOSize, int xiGoalSize)
    {
      mRepo = xiRepo;
      mGame = xiGame;
      mFullSize = xiFullSize;
      mXSize = xiXSize;
      mOSize = xiOSize;
      mGoalSize = xiGoalSize;
    }
  }

  @Test
  public void testPropNetSizes() throws Exception
  {
    PropNetSize[] lTests = new PropNetSize[] {new PropNetSize("base", "ticTacToe",      244,   168,   168,    57),
                                              new PropNetSize("base", "connectFour",    765,   619,   619,   299),
                                              new PropNetSize("base", "breakthrough",  1844,  1203,  1203,   142),
                                              new PropNetSize("base", "sudokuGrade1",  5483,  4446,  4399,  1722),
                                              new PropNetSize("base", "checkers",      8079,  4462,  4462,  1571),
                                              new PropNetSize("base", "reversi",      17795,  3997,  3997, 15195),
                                              new PropNetSize("base", "speedChess",   35416, 15710, 15710,  9512),
                                              new PropNetSize("base", "skirmishNew",  35541, 15705, 15705,  9567),
                                              new PropNetSize("base", "skirmish",     54143, 39649, 39649,  8897),
                                              new PropNetSize("base", "hex",          81507, 61164, 61164,  3250),
                                              new PropNetSize("base", "hexPie",       83023, 82342, 82335,  3300),
                                              };

    for (PropNetSize lTest : lTests)
    {
      List<Gdl> lGDL = new CloudGameRepository("games.ggp.org/" + lTest.mRepo).getGame(lTest.mGame).getRules();
      ForwardDeadReckonPropnetStateMachine lStateMachine = new ForwardDeadReckonPropnetStateMachine();
      lStateMachine.initialize(lGDL);
      assertEquals(lTest.mFullSize, lStateMachine.getFullPropNet().getComponents().size());
      int lXSize = lStateMachine.getXPropNet().getComponents().size();
      int lOSize = lStateMachine.getOPropNet().getComponents().size();
      assertEquals(lTest.mXSize, Math.max(lXSize, lOSize));
      assertEquals(lTest.mOSize, Math.min(lXSize, lOSize));
      assertEquals(lTest.mGoalSize, lStateMachine.getGoalPropNet().getComponents().size());
    }
  }

  protected Move move(String description)
  {
    String[] parts = description.split(" ");
    GdlConstant head = GdlPool.getConstant(parts[0]);
    if (parts.length == 1)
      return new Move(head);
    List<GdlTerm> body = new ArrayList<>();
    for (int i = 1; i < parts.length; i++)
    {
      body.add(GdlPool.getConstant(parts[i]));
    }
    return new Move(GdlPool.getFunction(head, body));
  }
}
