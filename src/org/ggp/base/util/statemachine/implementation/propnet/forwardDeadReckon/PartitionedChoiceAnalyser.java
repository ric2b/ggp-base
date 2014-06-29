package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;

/**
 * @author steve
 *  Analyse the state machine to determine if it is suitable for partitioned choice searching
 */
public class PartitionedChoiceAnalyser
{
  /**
   * Analyse the specified state machine to see if it is suitable for partitioned choice search,
   * and if it is generate the filter for that search partitioning
   * @param xiMachine
   * @return suitable filter if one is found else null
   */
  public static StateMachineFilter generatePartitionedChoiceFilter(ForwardDeadReckonPropnetStateMachine xiMachine)
  {
    //  Currently only supported on puzzles
    if ( xiMachine.getRoles().length > 1 )
    {
      return null;
    }

    //  TOTAL HACK FOR SUDOKU TEMPORARILY
    PartitionedChoiceStateMachineFilter filter = new PartitionedChoiceStateMachineFilter(xiMachine);

    ForwardDeadReckonLegalMoveSet[][] cellMoves = new ForwardDeadReckonLegalMoveSet[9][9];
    ForwardDeadReckonPropositionInfo[][] cellEnableProps = new ForwardDeadReckonPropositionInfo[9][9];

    for(int i = 0; i < 9; i++)
    {
      for(int j = 0; j < 9; j++)
      {
        cellMoves[i][j] = new ForwardDeadReckonLegalMoveSet(xiMachine.getFullPropNet().getActiveLegalProps(0));
        cellMoves[i][j].clear();
      }
    }

    Pattern cellMovePattern = Pattern.compile("\\( mark ([1-3]) ([1-3]) ([1-3]) ([1-3]) ([1-9])");
    for(ForwardDeadReckonLegalMoveInfo moveInfo : xiMachine.getFullPropNet().getMasterMoveList())
    {
      String moveString = moveInfo.move.toString();

      Matcher cellMoveMatcher = cellMovePattern.matcher(moveString);
      if ( !cellMoveMatcher.find() )
      {
        return null;
      }

      int boardX = Integer.parseInt(cellMoveMatcher.group(1));
      int boardY = Integer.parseInt(cellMoveMatcher.group(2));
      int cellX = Integer.parseInt(cellMoveMatcher.group(3));
      int cellY = Integer.parseInt(cellMoveMatcher.group(4));

      int x = (boardX-1)*3 + (cellX-1);
      int y = (boardY-1)*3 + (cellY-1);

      cellMoves[x][y].add(moveInfo);
    }

    Pattern cellBlankPropPattern = Pattern.compile("\\( cell ([1-3]) ([1-3]) ([1-3]) ([1-3]) b");
    for(ForwardDeadReckonPropositionInfo baseProp : xiMachine.getInfoSet())
    {
      String propString = baseProp.sentence.toString();

      Matcher cellBlankPropMatcher = cellBlankPropPattern.matcher(propString);
      if ( cellBlankPropMatcher.find() )
      {
        int boardX = Integer.parseInt(cellBlankPropMatcher.group(1));
        int boardY = Integer.parseInt(cellBlankPropMatcher.group(2));
        int cellX = Integer.parseInt(cellBlankPropMatcher.group(3));
        int cellY = Integer.parseInt(cellBlankPropMatcher.group(4));

        int x = (boardX-1)*3 + (cellX-1);
        int y = (boardY-1)*3 + (cellY-1);

        cellEnableProps[x][y] = baseProp;
      }
    }

    for(int i = 0; i < 9; i++)
    {
      for(int j = 0; j < 9; j++)
      {
        if ( cellEnableProps[i][j] != null )
        {
          //System.out.println("Partition: " + cellMoves[i][j]);
          //System.out.println("\tEnabler is: " + (cellEnableProps[i][j] == null ? "<NONE>" : cellEnableProps[i][j].sentence.toString()));
          filter.addPartition(cellMoves[i][j], cellEnableProps[i][j], null);
        }
      }
    }

    return filter;
  }
}
