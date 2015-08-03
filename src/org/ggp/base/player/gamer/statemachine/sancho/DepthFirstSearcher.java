package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Iterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * A very basic depth-first searcher.
 *
 * - Restricted to 2-player games.
 * - Restricted to fixed-sum games.
 * - Doesn't do alpha-beta pruning (yet).
 */
public class DepthFirstSearcher
{
  private static final Logger LOGGER = LogManager.getLogger();

  private final ForwardDeadReckonPropnetStateMachine mStateMachine;

  private int mStatesConsidered;
  private int mTerminalStatesConsidered;

  /**
   * Per-stack-frame variables that are created as members to avoid object allocation during recursion.
   */
  private ForwardDeadReckonInternalMachineState[] mStackState =
                                           new ForwardDeadReckonInternalMachineState[MCTSTree.MAX_SUPPORTED_TREE_DEPTH];
  private ForwardDeadReckonLegalMoveInfo[][] mStackJointMove =
                                                new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_TREE_DEPTH][];
  private ForwardDeadReckonLegalMoveInfo[][] mStackLegals =
                                                new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_TREE_DEPTH][];

  /**
   * Create a depth-first searcher.
   *
   * @param xiStateMachine - the state machine to use.
   */
  public DepthFirstSearcher(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    mStateMachine = xiStateMachine;

    int lNumRoles = mStateMachine.getRoles().length;
    assert(lNumRoles == 2) : "DFS only suitable for 2-player games";
    for (int lii = 0; lii < MCTSTree.MAX_SUPPORTED_TREE_DEPTH; lii++)
    {
      mStackState[lii]     = mStateMachine.createEmptyInternalState();
      mStackJointMove[lii] = new ForwardDeadReckonLegalMoveInfo[lNumRoles];
      mStackLegals[lii]    = new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_BRANCHING_FACTOR];
    }
  }

  /**
   * Perform a depth-first search from the specified state.
   *
   * @param xiState - the state.
   */
  public void search(ForwardDeadReckonInternalMachineState xiState)
  {
    LOGGER.info("Starting DFS");
    mStackState[0] = xiState;
    mStatesConsidered = 0;
    mTerminalStatesConsidered = 0;
    search(0);
    LOGGER.info("DFS complete after " + mStatesConsidered + " states of which " + mTerminalStatesConsidered + " were terminal");
  }

  /**
   * Internal recursing function to perform a depth-first search.
   *
   * @param xiDepth - the current depth below the requested search root.  Also defines the explicit stack variables to
   *                  use.
   */
  private void search(int xiDepth)
  {
    mStatesConsidered++;
    int lNumRoles = mStateMachine.getRoles().length;

    if (mStateMachine.isTerminal(mStackState[xiDepth]))
    {
      // !! ARR: mStateMachine.getGoal(lRole);
      mTerminalStatesConsidered++;
      return;
    }

    ForwardDeadReckonLegalMoveSet lLegals = mStateMachine.getLegalMoveSet(mStackState[xiDepth]);
    int lRoleWithChoice = -1;
    int lNumChoices = -1;
    for (int lii = 0; lii < lNumRoles; lii++)
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
      // No role had a choice.  Get the next state and recurse.
      mStateMachine.getNextState(mStackState[xiDepth], null, mStackJointMove[xiDepth], mStackState[xiDepth + 1]);
      search(xiDepth + 1);
    }
    else
    {
      // Role had a choice.  Iterate over all legal moves.
      for (int lii = 0; lii < lNumChoices; lii++)
      {
        // Set the move for the role with a choice.  (All the others are set already.)
        mStackJointMove[xiDepth][lRoleWithChoice] = mStackLegals[xiDepth][lii];

        // Get the next state and do a recursive (depth-first) search.
        mStateMachine.getNextState(mStackState[xiDepth], null, mStackJointMove[xiDepth], mStackState[xiDepth + 1]);
        search(xiDepth + 1);
      }
    }
  }
}
