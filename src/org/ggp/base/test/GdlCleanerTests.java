
package org.ggp.base.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.ggp.base.util.game.TestGameRepository;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.transforms.GdlCleaner;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;
import org.ggp.base.validator.StaticValidator;
import org.junit.Test;

public class GdlCleanerTests
{

  @Test
  public void testCleanNotDistinct() throws Exception
  {
    List<Gdl> description = new TestGameRepository().getGame("test_clean_not_distinct").getRules();
    description = GdlCleaner.run(description);

    StaticValidator.validateDescription(description);

    StateMachine sm = new ProverStateMachine();
    sm.initialize(description);
    MachineState state = sm.getInitialState();
    assertEquals(1, sm.getRoles().length);
    Role player = sm.getRoles()[0];
    assertEquals(1, sm.getLegalMoves(state, player).size());
    state = sm.getNextStates(state).get(0);
    assertTrue(sm.isTerminal(state));
    assertEquals(100, sm.getGoal(state, player));
  }

}
