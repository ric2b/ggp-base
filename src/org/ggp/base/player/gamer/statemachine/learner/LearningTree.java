package org.ggp.base.player.gamer.statemachine.learner;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.MCTSTree;
import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * A tree used for learning a position evaluation function.
 */
public class LearningTree
{

  private static final Logger LOGGER = LogManager.getLogger();

  private final HashMap<ForwardDeadReckonInternalMachineState, double[]> mScoreMap;
  private final ForwardDeadReckonPropnetStateMachine mStateMachine;
  private final TrainedEvaluationFunction mEvalFunc;
  private final TrainedEvaluationFunction mFrozenEvalFunc;
  private final Role mRole;
  private final int mRoleIndex;
  private final int mNumRoles;
  private final RoleOrdering mRoleOrdering;
  private final Random mRandom = new Random();

  /**
   * Per-stack-frame variables that are created as members to avoid object allocation during recursion.
   */
  private ForwardDeadReckonInternalMachineState[] mStackState =
                                           new ForwardDeadReckonInternalMachineState[MCTSTree.MAX_SUPPORTED_TREE_DEPTH];
  private ForwardDeadReckonLegalMoveInfo[][] mStackJointMove =
                                                new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_TREE_DEPTH][];
  private ForwardDeadReckonLegalMoveInfo[][] mStackLegals =
                                                new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_TREE_DEPTH][];

  public LearningTree(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                      TrainedEvaluationFunction xiEvalFunc,
                      TrainedEvaluationFunction xiFrozenEvalFunc)
  {
    mStateMachine = xiStateMachine;
    mEvalFunc = xiEvalFunc;
    mFrozenEvalFunc = xiFrozenEvalFunc;

    mRoleOrdering = xiStateMachine.getRoleOrdering();
    mRole = mRoleOrdering.getOurRole();
    mRoleIndex = mRoleOrdering.getOurRawRoleIndex();
    mNumRoles = xiStateMachine.getRoles().length;

    mScoreMap = new HashMap<>();

    for (int lii = 0; lii < MCTSTree.MAX_SUPPORTED_TREE_DEPTH; lii++)
    {
      mStackState[lii]     = mStateMachine.createEmptyInternalState();
      mStackJointMove[lii] = new ForwardDeadReckonLegalMoveInfo[mNumRoles];
      mStackLegals[lii]    = new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_BRANCHING_FACTOR];
    }
  }

  /**
   * Perform a depth-first search from the specified state.
   *
   * @param xiState - the state.
   * @param xiCutOff - cut-off depth.
   */
  public double[] search(ForwardDeadReckonInternalMachineState xiState, int xiCutOff)
  {
    mStackState[0] = new ForwardDeadReckonInternalMachineState(xiState);
    return search(0, xiCutOff);
  }

  /**
   * Internal recursing function to perform a depth-first search.
   *
   * @param xiDepth  - the current depth below the requested search root.  Also defines the explicit stack variables to
   *                   use.
   * @param xiCutOff - the maximum depth to search.
   */
  private double[] search(int xiDepth, int xiCutOff)
  {
    if (mScoreMap.containsKey(mStackState[xiDepth]))
    {
      return mScoreMap.get(mStackState[xiDepth]);
    }

    // If the state is already terminal, train the evaluation function on this value and return it.  Although we'll
    // never ask the evaluation function about terminal states, it *massively* helps to train them on it.
    if (mStateMachine.isTerminal(mStackState[xiDepth]))
    {
      double[] lGoals = getGoals(mStackState[xiDepth]);
      mEvalFunc.sample(mStackState[xiDepth], lGoals);
      mScoreMap.put(new ForwardDeadReckonInternalMachineState(mStackState[xiDepth]), lGoals);
      return lGoals;
    }

    // If we've reached the maximum search depth, just return the value from the evaluation function.  (Don't train the
    // evaluation function at this point - it would just blindly reinforce the current beliefs.)
    if (xiDepth >= xiCutOff)
    {
      return mFrozenEvalFunc.evaluate(mStackState[xiDepth]);
    }

    // Iterate over all the children, recursing.
    ForwardDeadReckonLegalMoveSet lLegals = mStateMachine.getLegalMoveSet(mStackState[xiDepth]);
    int lRoleWithChoice = -1;
    int lNumChoices = -1;
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      // Store a legal move for this role.  For all roles but one, this will be the only move.  For the role with a
      // choice, this will be the first legal move.
      mStackJointMove[xiDepth][lii] = lLegals.getContents(lii).iterator().next();

      // Check if this is the role with a choice.
      if (lLegals.getNumChoices(lii) > 1)
      {
        assert(lRoleWithChoice == -1) : "More than 1 role has a choice";
        lRoleWithChoice = lii;
        lNumChoices = 0;

        // Copy out the legals before they're destroyed in the recursive call.
        Iterator<ForwardDeadReckonLegalMoveInfo> lIterator = lLegals.getContents(lii).iterator();
        while (lIterator.hasNext())
        {
          mStackLegals[xiDepth][lNumChoices++] = lIterator.next();
        }
      }
    }

    if (lRoleWithChoice == -1)
    {
      // No role had a choice.  Return the value of the only child.  Also train the evaluation function.  Although
      // we'll never ask it about this state, it still helps to train it.
      mStateMachine.getNextState(mStackState[xiDepth], null, mStackJointMove[xiDepth], mStackState[xiDepth + 1]);
      double[] lGoals = search(xiDepth + 1, xiCutOff);
      mEvalFunc.sample(mStackState[xiDepth], lGoals);
      mScoreMap.put(new ForwardDeadReckonInternalMachineState(mStackState[xiDepth]), lGoals);
      return lGoals;
    }

    // A role had a choice.  That role is trying to maximise its value.
    double[] lBestGoals = null;
    for (int lii = 0; lii < lNumChoices; lii++)
    {
      // Set the move for the role with a choice.  (All the others are set already.)
      mStackJointMove[xiDepth][lRoleWithChoice] = mStackLegals[xiDepth][lii];

      // Get the next state and do a recursive (depth-first) search.
      mStateMachine.getNextState(mStackState[xiDepth], null, mStackJointMove[xiDepth], mStackState[xiDepth + 1]);
      double[] lChildGoals = search(xiDepth + 1, xiCutOff);

      if ((lBestGoals == null) || (lChildGoals[lRoleWithChoice] > lBestGoals[lRoleWithChoice]))
      {
        lBestGoals = lChildGoals;
      }
    }

    mEvalFunc.sample(mStackState[xiDepth], lBestGoals);
    mScoreMap.put(new ForwardDeadReckonInternalMachineState(mStackState[xiDepth]), lBestGoals);
    return lBestGoals;
  }

  private double[] getGoals(ForwardDeadReckonInternalMachineState xiState)
  {
    double[] lGoals = new double[mNumRoles];
    Role[] lRoles = mStateMachine.getRoles();
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      lGoals[lii] = mStateMachine.getGoal(xiState, lRoles[lii]);
    }
    return lGoals;
  }

  public double getAverageError()
  {
    double lTotalError = 0;
    for (Object lState : mScoreMap.keySet())
    {
      lTotalError += getTotalDiff(mEvalFunc.evaluate((ForwardDeadReckonInternalMachineState)lState),
                                  mScoreMap.get(lState));
    }
    return lTotalError / mScoreMap.size();
  }

  private double getTotalDiff(double[] xiA, double[]xiB)
  {
    double lTotalDiff = 0;
    for (int lii = 0; lii < xiA.length; lii++)
    {
      lTotalDiff += Math.abs(xiA[lii] - xiB[lii]);
    }
    return lTotalDiff;
  }

  public int getWrongMoves(boolean xiDump)
  {
    int lBadStates = 0;
    for (Object lState : mScoreMap.keySet())
    {
      if (!checkState((ForwardDeadReckonInternalMachineState)lState, xiDump))
      {
        lBadStates++;
      }
    }
    return lBadStates;
  }

  private boolean checkState(ForwardDeadReckonInternalMachineState xiState, boolean xiDump)
  {
    // There are no moves to make in a terminal state, so we can't select the wrong one.
    if (mStateMachine.isTerminal(xiState))
    {
      return true;
    }

    int lDepth = 0;
    mStackState[lDepth] = new ForwardDeadReckonInternalMachineState(xiState);

    // Iterate over all the children.
    ForwardDeadReckonLegalMoveSet lLegals = mStateMachine.getLegalMoveSet(mStackState[lDepth]);
    int lRoleWithChoice = -1;
    int lNumChoices = -1;
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      // Store a legal move for this role.  For all roles but one, this will be the only move.  For the role with a
      // choice, this will be the first legal move.
      mStackJointMove[lDepth][lii] = lLegals.getContents(lii).iterator().next();

      // Check if this is the role with a choice.
      if (lLegals.getNumChoices(lii) > 1)
      {
        assert(lRoleWithChoice == -1) : "More than 1 role has a choice";
        lRoleWithChoice = lii;
        lNumChoices = 0;

        // Copy out the legals before they're destroyed.
        Iterator<ForwardDeadReckonLegalMoveInfo> lIterator = lLegals.getContents(lii).iterator();
        while (lIterator.hasNext())
        {
          mStackLegals[lDepth][lNumChoices++] = lIterator.next();
        }
      }
    }

    if (lRoleWithChoice == -1)
    {
      // No role had a choice.  Therefore, we can't select the wrong move.
      return true;
    }

    // A role had a choice.  That role is trying to maximise its value.
    boolean lRight = false;
    String lError = "";
    double lBestValue = -1;
    for (int lii = 0; lii < lNumChoices; lii++)
    {
      // Set the move for the role with a choice.  (All the others are set already.)
      mStackJointMove[lDepth][lRoleWithChoice] = mStackLegals[lDepth][lii];

      // Get the next state.
      mStateMachine.getNextState(mStackState[lDepth], null, mStackJointMove[lDepth], mStackState[lDepth + 1]);
      double lValue = mEvalFunc.evaluate(mStackState[lDepth + 1])[lRoleWithChoice];
      if (lValue > lBestValue)
      {
        // We'd chose this move.
        lBestValue = lValue;
        lRight = mScoreMap.get(mStackState[lDepth + 1])[lRoleWithChoice] >= mScoreMap.get(mStackState[lDepth])[lRoleWithChoice];
        lError = "  Choosing role score fell from " + mScoreMap.get(mStackState[lDepth])[lRoleWithChoice] +
                 " to " + mScoreMap.get(mStackState[lDepth + 1])[lRoleWithChoice] +
                 " because we thought the afterstate had value " + lValue +
                 " when playing " + mStackJointMove[lDepth][lRoleWithChoice] +
                 " in state " + mStackState[lDepth] +
                 " giving afterstate " + mStackState[lDepth + 1];
      }
    }

    if ((!lRight) && (xiDump))
    {
      LOGGER.warn(lError);
    }
    return lRight;
  }

  public ForwardDeadReckonInternalMachineState epsilonGreedySelection(ForwardDeadReckonInternalMachineState xiState,
                                                                      double xiEpsilon,
                                                                      boolean xiDumpMoves)
  {
    int lDepth = 0;
    mStackState[lDepth] = xiState;

    // Iterate over all the children.
    ForwardDeadReckonLegalMoveSet lLegals = mStateMachine.getLegalMoveSet(mStackState[lDepth]);
    int lRoleWithChoice = -1;
    int lNumChoices = -1;
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      // Store a legal move for this role.  For all roles but one, this will be the only move.  For the role with a
      // choice, this will be the first legal move.
      mStackJointMove[lDepth][lii] = lLegals.getContents(lii).iterator().next();

      // Check if this is the role with a choice.
      if (lLegals.getNumChoices(lii) > 1)
      {
        assert(lRoleWithChoice == -1) : "More than 1 role has a choice";
        lRoleWithChoice = lii;
        lNumChoices = 0;

        // Copy out the legals before they're destroyed.
        Iterator<ForwardDeadReckonLegalMoveInfo> lIterator = lLegals.getContents(lii).iterator();
        while (lIterator.hasNext())
        {
          mStackLegals[lDepth][lNumChoices++] = lIterator.next();
        }
      }
    }

    if (lRoleWithChoice == -1)
    {
      // No role had a choice.
      if (xiDumpMoves) LOGGER.info(Arrays.toString(mStackJointMove[lDepth]));
      mStateMachine.getNextState(mStackState[lDepth], null, mStackJointMove[lDepth], mStackState[lDepth + 1]);
      return new ForwardDeadReckonInternalMachineState(mStackState[lDepth + 1]);
    }

    if (mRandom.nextDouble() < xiEpsilon)
    {
      // Pick a random choice.
      mStackJointMove[lDepth][lRoleWithChoice] = mStackLegals[lDepth][mRandom.nextInt(lNumChoices)];
      mStateMachine.getNextState(mStackState[lDepth], null, mStackJointMove[lDepth], mStackState[lDepth + 1]);
    }
    else
    {
      double lBestValue = -1;
      int lBestMoveIndex = -1;
      for (int lii = 0; lii < lNumChoices; lii++)
      {
        // Set the move for the role with a choice.  (All the others are set already.)
        mStackJointMove[lDepth][lRoleWithChoice] = mStackLegals[lDepth][lii];

        // Get the next state.
        mStateMachine.getNextState(mStackState[lDepth], null, mStackJointMove[lDepth], mStackState[lDepth + 1]);
        double lValue = mEvalFunc.evaluate(mStackState[lDepth + 1])[lRoleWithChoice];
        if (lValue > lBestValue)
        {
          // We'd chose this move.
          lBestValue = lValue;
          lBestMoveIndex =  lii;
        }
      }

      // Get the state for the best move.
      mStackJointMove[lDepth][lRoleWithChoice] = mStackLegals[lDepth][lBestMoveIndex];
      mStateMachine.getNextState(mStackState[lDepth], null, mStackJointMove[lDepth], mStackState[lDepth + 1]);
    }

    if (xiDumpMoves) LOGGER.info(Arrays.toString(mStackJointMove[lDepth]));
    return new ForwardDeadReckonInternalMachineState(mStackState[lDepth + 1]);
  }
}
