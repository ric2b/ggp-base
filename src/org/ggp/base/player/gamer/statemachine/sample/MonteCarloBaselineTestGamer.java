
package org.ggp.base.player.gamer.statemachine.sample;

import java.util.List;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.cache.CachedStateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;

public class MonteCarloBaselineTestGamer extends SampleGamer
{

  // This is the default State Machine
  @Override
  public StateMachine getInitialStateMachine()
  {
    GamerLogger.setFileToDisplay("StateMachine");

    //ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
    //return new CachedStateMachine(new NormalizingProverStateMachine());
    //return new CachedStateMachine(new ProverStateMachine());
    return new ProverStateMachine();
  }

  /**
   * Employs a simple sample "Monte Carlo" algorithm.
   */
  @Override
  public Move stateMachineSelectMove(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    StateMachine theMachine = getStateMachine();
    long start = System.currentTimeMillis();
    long finishBy = timeout - 1000;
    long numSamples = 0;

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

    long stop = System.currentTimeMillis();

    GamerLogger.log("StateMachine", "Num MonteCarlo samples (baseline): " +
                                    numSamples);
    notifyObservers(new GamerSelectedMoveEvent(moves, selection, stop - start));
    return selection;
  }

  private int[] depth = new int[1];

  int performDepthChargeFromMove(MachineState theState, Move myMove)
  {
    StateMachine theMachine = getStateMachine();
    try
    {
      MachineState finalState = theMachine.performDepthCharge(theMachine
          .getRandomNextState(theState, getRole(), myMove), depth);
      return theMachine.getGoal(finalState, getRole());
    }
    catch (Exception e)
    {
      e.printStackTrace();
      return 0;
    }
  }

}
