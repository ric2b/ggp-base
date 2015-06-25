package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public class SimplePieceHeuristic extends PieceHeuristic
{
  private TreeNode rootNode;

  @Override
  public void getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                ForwardDeadReckonInternalMachineState xiPreviousState,
                                ForwardDeadReckonInternalMachineState xiReferenceState,
                                HeuristicInfo resultInfo)

  {
    double total = 0;
    double rootTotal = 0;

    for (int i = 0; i < numRoles; i++)
    {
      // Set the initial heuristic value for this role according to the difference in number of pieces between this
      // state and the current state in the tree root.
      double numPieces = pieceSets[i].getValue(xiState);
      resultInfo.heuristicValue[i] = numPieces - rootPieceValues[i];

      total += numPieces;
      rootTotal += rootPieceValues[i];

      // Counter-weight exchange sequences slightly to remove the first-capture bias, at least to first order.
      double previousNumPieces = pieceSets[i].getValue(xiPreviousState);
      if (numPieces == rootPieceValues[i] &&
          previousNumPieces < rootPieceValues[i])
      {
        resultInfo.heuristicValue[i] += 0.1;
        total += 0.1;
      }
      else if (numPieces == rootPieceValues[i] &&
               previousNumPieces > rootPieceValues[i])
      {
        resultInfo.heuristicValue[i] -= 0.1;
        total -= 0.1;
      }
    }

    for (int i = 0; i < numRoles; i++)
    {
      if (rootTotal != total)
      {
        // There has been an overall change in the number of pieces.  Calculate the proportion of that total gained/lost
        // by this role and use that to generate a new average heuristic value for the role.
        double proportion = (resultInfo.heuristicValue[i] - (total - rootTotal) / numRoles) / (total / numRoles);
        resultInfo.heuristicValue[i] = 100 / (1 + Math.exp(-proportion * 10));
      }
      else
      {
        // There has been no overall change to the number of pieces.  Assume an average value.
        // !! ARR Why?
        resultInfo.heuristicValue[i] = 50;
      }

      // Normalize against the root score since this is relative to the root state material balance.  Only do this if
      // the root has had enough visits to have a credible estimate.
      if (rootNode != null && rootNode.mNumVisits > 50)
      {
        // Set the average score for the child to the average score of the root displaced towards the extremities
        // (0/100) by a proportion of the amount that it currently deviates from the extremities, where that proportion
        // is equal to the proportion by which the heuristic value deviates from the centre.
        //
        // This assumes that the root's score is a very good basis as the initial estimate for this child node and is
        // certainly better than the heuristic value alone.
        double rootAverageScore = rootNode.getAverageScore(i);
        if (resultInfo.heuristicValue[i] > 50)
        {
          resultInfo.heuristicValue[i] = rootAverageScore +
                                (100 - rootAverageScore) *
                                (resultInfo.heuristicValue[i] - 50) /
                                50;
        }
        else
        {
          resultInfo.heuristicValue[i] = rootAverageScore -
                                (rootAverageScore) *
                                (50 - resultInfo.heuristicValue[i]) /
                                50;
        }
      }
    }

    resultInfo.heuristicWeight = heuristicSampleWeight;//*2;
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode)
  {
    rootNode = xiNode;
    super.newTurn(xiState, xiNode);
  }
}