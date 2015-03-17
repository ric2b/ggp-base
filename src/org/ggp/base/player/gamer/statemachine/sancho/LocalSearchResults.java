package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;

/**
 * @author steve
 * Information structure detailing local search results
 */
public class LocalSearchResults implements LocalRegionDefiner
{
  /**
   * Role for whom a (local) forced win was found or -1 if none
   */
  public int winForRole;
  /**
   * Role for whom a (local) tenuki is a loss or -1 if none
   */
  public int tenukiLossForRole;
  /**
   * Seeding move for the local space
   */
  public ForwardDeadReckonLegalMoveInfo seedMove;
  /**
   * State from which the search began
   */
  public ForwardDeadReckonInternalMachineState startState;
  /**
   * State from which the start state was a child (or null)
   */
  public ForwardDeadReckonInternalMachineState choiceFromState;
  /**
   * Move found to be a win (or null if none)
   */
  public ForwardDeadReckonLegalMoveInfo winningMove;
  /**
   * Depth of search result found at
   */
  public int atDepth;

  ForwardDeadReckonLegalMoveInfo jointSearchSecondarySeed;
  int searchRadius;
  LocalRegionSearcher searchProvider;
  ForwardDeadReckonLegalMoveInfo[] winPath;
  ForwardDeadReckonLegalMoveSet[] relevantMovesForWin;

  /**
   * Make this object a shallow copy of source
   * @param source
   */
  public void copyFrom(LocalSearchResults source)
  {
    winForRole = source.winForRole;
    tenukiLossForRole = source.tenukiLossForRole;
    seedMove = source.seedMove;
    startState = source.startState;
    winningMove = source.winningMove;
    atDepth = source.atDepth;
    jointSearchSecondarySeed = source.jointSearchSecondarySeed;
    searchRadius = source.searchRadius;
    searchProvider = source.searchProvider;
    winPath = source.winPath;
    choiceFromState = source.choiceFromState;
    if ( source.relevantMovesForWin == null )
    {
      relevantMovesForWin = null;
    }
    else
    {
      relevantMovesForWin = new ForwardDeadReckonLegalMoveSet[source.searchRadius+1];

      for(int i = 0; i <= source.searchRadius; i++)
      {
        relevantMovesForWin[i] = new ForwardDeadReckonLegalMoveSet(source.relevantMovesForWin[i]);
        relevantMovesForWin[i].copy(source.relevantMovesForWin[i]);
      }
    }
  }

  @Override
  public boolean isLocal(ForwardDeadReckonLegalMoveInfo xiMove)
  {
    boolean result = (seedMove == null ? null : (searchProvider.getMoveDistance(seedMove, xiMove) <= searchRadius));
    if ( jointSearchSecondarySeed != null )
    {
      result |= (searchProvider.getMoveDistance(jointSearchSecondarySeed, xiMove) <= searchRadius);
    }
    return result;
  }

  @Override
  public boolean canInfluenceFoundResult(ForwardDeadReckonLegalMoveInfo xiMove)
  {
    //  TEMP until I can sort out the correct semantics
    //return true;
    if ( relevantMovesForWin == null )
    {
      return true;
    }

    for(int i = 1; i <= searchRadius; i++)
    {
      for(ForwardDeadReckonLegalMoveInfo relevantMove : relevantMovesForWin[i].getContents(searchProvider.roleOrdering.roleIndexToRawRoleIndex(winForRole)))
      {
        //  Could this move have been disturbed by the move being checked?
        //  The check for the very last (winning move) has a tighter bound since
        //  the interaction has to be on the winning role's move (to prevent it)
        //  not the optional role's (to counter it after it is played)
        //  TODO - validate this holds in all games and is not an unintended Breakthrough
        //  category property!
        //if ( searchProvider.getMoveDistance(winPathMove, xiMove) < i + (i == searchRadius ? 1 : 0))
        if ( searchProvider.getMoveDistance(relevantMove, xiMove) <= i+1)
        {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public boolean seedMayEnableResult()
  {
    return canInfluenceFoundResult(seedMove);
    //  TEMP until I can sort out the correct semantics
    //return true;
//    if ( winPath == null )
//    {
//      return true;
//    }
//
//    for(int i = 1; i <= searchRadius; i++)
//    {
//      ForwardDeadReckonLegalMoveInfo winPathMove = winPath[i];
//      if ( winPathMove != null && winPathMove.inputProposition != null )
//      {
//        //  Could this move have been enabled by the seed move?
//        //  Note <= here not < since the seed is assume to preceed
//        //  the found move path
//        if ( searchProvider.getMoveDistance(winPathMove, seedMove) <= searchRadius )
//        {
//          return true;
//        }
//        break;
//      }
//    }
//
//    return false;
  }
}