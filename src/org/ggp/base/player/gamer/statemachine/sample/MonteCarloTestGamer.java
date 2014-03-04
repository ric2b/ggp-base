
package org.ggp.base.player.gamer.statemachine.sample;

import java.util.List;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.propnet.polymorphic.learning.LearningComponent;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;

public class MonteCarloTestGamer extends SampleGamer
{

  private TestForwardDeadReckonPropnetStateMachine underlyingStateMachine;

  @Override
  public void stateMachineMetaGame(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    boolean lDummy = false;
    if (lDummy)
    {
      //underlyingStateMachine.recreate(new LearningComponentFactory());
      MachineState initial = underlyingStateMachine.getInitialState();

      underlyingStateMachine.setRandomSeed(1);
      LearningComponent.getCount = 0;
      underlyingStateMachine.performDepthCharge(initial, null);
      System.out.println("#pre-learning gets for one depth charge: " +
                         LearningComponent.getCount);

      if (true)
      {
        final int learningCount = 5;

        for (int i = 0; i < learningCount; i++)
        {
          stateMachineSelectMoveInternal(Math.min(System.currentTimeMillis() + 2000,
                                                  timeout),
                                         false);

          underlyingStateMachine.Optimize();
        }
      }

      initial = underlyingStateMachine.getInitialState();

      underlyingStateMachine.setRandomSeed(1);
      LearningComponent.getCount = 0;
      underlyingStateMachine.performDepthCharge(initial, null);
      System.out.println("#post-learning gets for one depth charge: " +
                         LearningComponent.getCount);

      System.gc();
      //underlyingStateMachine.recreate(new RuntimeOptimizedComponentFactory());
      underlyingStateMachine.getInitialState();
      System.gc();
    }
  }

  // This is the default State Machine
  @Override
  public StateMachine getInitialStateMachine()
  {
    GamerLogger.setFileToDisplay("StateMachine");

    underlyingStateMachine = new TestForwardDeadReckonPropnetStateMachine();
    //ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
    //return new CachedStateMachine(underlyingStateMachine);
    return underlyingStateMachine;
  }

  /**
   * Employs a simple sample "Monte Carlo" algorithm.
   */
  @Override
  public Move stateMachineSelectMove(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    return stateMachineSelectMoveInternal(timeout, true);
  }

  private Move stateMachineSelectMoveInternal(long timeout, boolean notify)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    StateMachine theMachine = getStateMachine();
    long start = System.currentTimeMillis();
    long finishBy = timeout - 1000;
    long numSamples = 0;

    ProfilerContext.resetStats();

    List<Move> moves = theMachine.getLegalMoves(getCurrentState(), getRole());
    Move selection = moves.get(0);

    if (moves.size() > 1)
    {
      int[] moveTotalPoints = new int[moves.size()];
      int[] moveTotalAttempts = new int[moves.size()];

      // Perform depth charges for each candidate move, and keep track
      // of the total score and total attempts accumulated for each move.
      for (int i = 0; true; i = (i + 1) % moves.size())
      {
        if (System.currentTimeMillis() > finishBy)
          break;

        int theScore = performDepthChargeFromMove(getCurrentState(),
                                                  moves.get(i));
        moveTotalPoints[i] += theScore;
        moveTotalAttempts[i] += 1;

        numSamples++;

        theMachine.updateRoot(getCurrentState());
      }

      // Compute the expected score for each move.
      double[] moveExpectedPoints = new double[moves.size()];
      for (int i = 0; i < moves.size(); i++)
      {
        moveExpectedPoints[i] = (double)moveTotalPoints[i] /
                                moveTotalAttempts[i];
      }

      // Find the move with the best expected score.
      int bestMove = 0;
      double bestMoveScore = moveExpectedPoints[0];
      for (int i = 1; i < moves.size(); i++)
      {
        if (moveExpectedPoints[i] > bestMoveScore)
        {
          bestMoveScore = moveExpectedPoints[i];
          bestMove = i;
        }
      }
      selection = moves.get(bestMove);
    }
    else
    {
      System.out.println("Single move available in state: " +
                         getCurrentState());
    }

    long stop = System.currentTimeMillis();

    GamerLogger.log("StateMachine", "Num MonteCarlo samples (test): " +
                                    numSamples);
    GamerLogger.log("StateMachine", "Stats: ");
    GamerLogger.log("StateMachine", underlyingStateMachine.getStats()
        .toString());
    GamerLogger.log("StateMachine",
                    "Total num gates propagated: " +
                        underlyingStateMachine.totalNumGatesPropagated);
    GamerLogger.log("StateMachine", "Total num propagates: " +
                                    underlyingStateMachine.totalNumPropagates);

    if (ProfilerContext.getContext() != null)
    {
      GamerLogger.log("GamePlayer", "Profile stats: \n" +
                                    ProfilerContext.getContext().toString());
    }

    if (notify)
    {
      notifyObservers(new GamerSelectedMoveEvent(moves, selection, stop -
                                                                   start));
    }

    return selection;
  }

  private int[] depth = new int[1];

  int performDepthChargeFromMove(MachineState theState, Move myMove)
  {
    try
    {
      int result = underlyingStateMachine
          .getDepthChargeResult(underlyingStateMachine
                                    .getRandomNextState(theState,
                                                        getRole(),
                                                        myMove),
                                getRole(),
                                0,
                                null,
                                depth);

      //System.out.println("Gamer depth charge final state: " + finalState + " has value " + result);
      return result;
    }
    catch (Exception e)
    {
      e.printStackTrace();
      return 0;
    }
  }

}
