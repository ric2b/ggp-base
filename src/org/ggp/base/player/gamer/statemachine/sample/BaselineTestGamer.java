
package org.ggp.base.player.gamer.statemachine.sample;

import java.util.List;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.profile.ProfilerSampleSetSimple;
import org.ggp.base.util.profile.ProfileSection;

public class BaselineTestGamer extends SampleGamer
{

  // This is the default State Machine
  public StateMachine getInitialStateMachine()
  {
    ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
    //return new CachedStateMachine(new NormalizingProverStateMachine());
    return new ProverStateMachine();
  }

  /**
   * This function is called at the start of each round You are required to
   * return the Move your player will play before the timeout.
   */
  public Move stateMachineSelectMove(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    // We get the current start time
    long start = System.currentTimeMillis();
    long finishBy = timeout - 1000;
    long numSamples = 0;
    List<Move> moves = null;

    ProfilerContext.resetStats();

    /**
     * We put in memory the list of legal moves from the current state. The
     * goal of every stateMachineSelectMove() is to return one of these moves.
     * The choice of which Move to play is the goal of GGP.
     */
    while (System.currentTimeMillis() < finishBy)
    {
      numSamples++;
      moves = getStateMachine().getLegalMoves(getCurrentState(), getRole());
    }

    // SampleLegalGamer is very simple : it picks the first legal move
    Move selection = moves.get(0);

    // We get the end time
    // It is mandatory that stop<timeout
    long stop = System.currentTimeMillis();

    GamerLogger.log("GamePlayer", "Num legal move generations: " + numSamples);
    GamerLogger.log("GamePlayer", "Profile stats: \n" +
                                  ProfilerContext.getContext().toString());

    /**
     * These are functions used by other parts of the GGP codebase You
     * shouldn't worry about them, just make sure that you have moves,
     * selection, stop and start defined in the same way as this example, and
     * copy-paste these two lines in your player
     */
    notifyObservers(new GamerSelectedMoveEvent(moves, selection, stop - start));
    return selection;
  }

}
