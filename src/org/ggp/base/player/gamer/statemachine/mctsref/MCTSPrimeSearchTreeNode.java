package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public class MCTSPrimeSearchTreeNode extends SearchTreeNode<MCTSPrimeSearchTree>
{
  private final double ROOT2 = Math.sqrt(2);
  private final double A = 8*(Math.PI - 3)/(3*Math.PI*(4 - Math.PI));
  private final int THRESHOLD_VISITS = 20;

  public MCTSPrimeSearchTreeNode(MCTSPrimeSearchTree xiTree,
                                 ForwardDeadReckonInternalMachineState xiState,
                                 int xiChoosingRole)
  {
    super(xiTree, xiState, xiChoosingRole);
  }

  @Override
  protected
  void updateScore(SearchTreeNode<MCTSPrimeSearchTree> xiChild, double[] xiPlayoutResult)
  {
    updateScoreNormalDistAllCompare(xiChild, xiPlayoutResult);
  }

  private double getStdDeviationEstimate()
  {
    return complete ? 0 : 141*Math.sqrt(1/((double)numVisits+1));
  }

  private void updateScoreNormalDist(SearchTreeNode<MCTSPrimeSearchTree> xiChild, double[] xiPlayoutResult)
  {
    if ( numVisits < THRESHOLD_VISITS )
    {
      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] = (scoreVector[i]*numVisits + xiPlayoutResult[i])/(numVisits+1);
      }
    }
    else
    {
      double pivot;
      double weightTotal = 0;
      double pivotStdDeviationEstimate = 0;
      SearchTreeNode pivotChild = null;

      pivot = -Double.MAX_VALUE;
      for(int i = 0; i < children.length; i++)
      {
        SearchTreeNode<MCTSPrimeSearchTree> child = children[i];
        if ( child.numVisits > 0 && child.scoreVector[choosingRole] > pivot )
        {
          pivot = child.scoreVector[choosingRole];
          pivotStdDeviationEstimate = ((MCTSPrimeSearchTreeNode)child).getStdDeviationEstimate();
          pivotChild = child;
        }
      }

      assert(pivotChild!=null);

//      if ( pivotStdDeviationEstimate == 0 )
//      {
//        for(int i = 0; i < scoreVector.length; i++)
//        {
//          scoreVector[i] = pivotChild.scoreVector[i];
//        }
//        return;
//      }

      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] = 0;
      }

      for(int i = 0; i < children.length; i++)
      {
        SearchTreeNode<MCTSPrimeSearchTree> child = children[i];

        if ( child.numVisits > 0 )
        {
          double weight;
          double normalizedPivot = child.scoreVector[choosingRole] - pivot;
          double childStdDeviationEstimate = ((MCTSPrimeSearchTreeNode)child).getStdDeviationEstimate();

          weight = cumulativeProb(normalizedPivot, pivotStdDeviationEstimate + childStdDeviationEstimate);

          assert(weight >= 0);
          weightTotal += weight;

          for(int j = 0; j < scoreVector.length; j++)
          {
            scoreVector[j] += child.scoreVector[j]*weight;
          }
        }
      }

      assert(weightTotal > 0);
      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] /= weightTotal;
      }
    }
  }

  private void updateScoreNormalDistAllCompare(SearchTreeNode<MCTSPrimeSearchTree> xiChild, double[] xiPlayoutResult)
  {
    if ( numVisits < THRESHOLD_VISITS )
    {
      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] = (scoreVector[i]*numVisits + xiPlayoutResult[i])/(numVisits+1);
      }
    }
    else
    {
      double weightTotal = 0;

      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] = 0;
      }

      for(int i = 0; i < children.length; i++)
      {
        MCTSPrimeSearchTreeNode child = (MCTSPrimeSearchTreeNode)children[i];

        if ( child.numVisits > 0 )
        {
          double weight = 1;

          for(int j = 0; j < children.length; j++)
          {
            if ( j != i )
            {
              double scoreDiff = children[j].scoreVector[choosingRole] - child.scoreVector[choosingRole];
              double jExceedsI = cumulativeProb(scoreDiff, child.getStdDeviationEstimate() + ((MCTSPrimeSearchTreeNode)children[j]).getStdDeviationEstimate());

              weight *= (1-jExceedsI);
            }
          }

          assert(weight >= 0);
          weightTotal += weight;

          for(int j = 0; j < scoreVector.length; j++)
          {
            scoreVector[j] += child.scoreVector[j]*weight;
          }
        }
      }

      System.out.println("weigth total = " + weightTotal);

      assert(weightTotal > 0);
      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] /= weightTotal;
      }
    }
  }

  private void updateScoreNormalizedSelection(SearchTreeNode<MCTSPrimeSearchTree> xiChild, double[] xiPlayoutResult)
  {
    if ( numVisits < THRESHOLD_VISITS )
    {
      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] = (scoreVector[i]*numVisits + xiPlayoutResult[i])/(numVisits+1);
      }
    }
    else
    {
      double pivot;
      double weightTotal = 0;
      double topVisited = 0;
      double floor = -1;
      SearchTreeNode topChild = null;

      pivot = -Double.MAX_VALUE;
      for(int i = 0; i < children.length; i++)
      {
        SearchTreeNode<MCTSPrimeSearchTree> child = children[i];
        if ( child.numVisits > 0 )
        {
          if ( child.scoreVector[choosingRole] > pivot || (child.scoreVector[choosingRole] == pivot && child.complete))
          {
            pivot = child.scoreVector[choosingRole];
            topVisited = child.numVisits;
            topChild = child;
          }
          if ( child.complete && child.scoreVector[choosingRole] > floor )
          {
            floor = child.scoreVector[choosingRole];
          }
        }
      }

      assert(topChild != null);
      if ( topChild.complete )
      {
        for(int i = 0; i < scoreVector.length; i++)
        {
          scoreVector[i] = topChild.scoreVector[i];
        }

        return;
      }

      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] = 0;
      }

      for(int i = 0; i < children.length; i++)
      {
        SearchTreeNode<MCTSPrimeSearchTree> child = children[i];

        if ( child.numVisits > 0 && child.scoreVector[choosingRole] > floor )
        {
          double weight;
          double interim = (pivot - child.scoreVector[choosingRole])/(100*EXPLORATION_BIAS*Math.sqrt(2*numVisits)) + Math.sqrt(1/topVisited);
          weight = 1/(interim*interim);
          assert(weight >= 0);
          weightTotal += weight;

          for(int j = 0; j < scoreVector.length; j++)
          {
            scoreVector[j] += child.scoreVector[j]*weight;
          }
        }
      }

      assert(weightTotal > 0);
      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] /= weightTotal;
      }
    }
  }

  void validateScoreVector()
  {
    if ( children != null )//&& !hasUnattributedSample )
    {
      double low = Double.MAX_VALUE;
      double high = -Double.MAX_VALUE;

      for(SearchTreeNode<MCTSPrimeSearchTree> child : children)
      {
        if ( child.numVisits > 0 )
        {
          if ( child.scoreVector[0] < low )
          {
            low = child.scoreVector[0];
          }
          if ( child.scoreVector[0] > high )
          {
            high = child.scoreVector[0];
          }
        }
      }

      assert(low <= scoreVector[0]);
      assert(high >= scoreVector[0]);
    }
  }

  private double cumulativeProb(double value, double stdDev)
  {
    if ( stdDev == 0 )
    {
      return (value >= 0 ? 1 : 0);
    }
    return (1 + erf(value/(ROOT2*stdDev)))/2;
  }

  private double erf(double x)
  {
    double xSquared = x*x;
    double body = Math.sqrt(1 - Math.exp(-xSquared*((4/Math.PI + A*xSquared)/(1 + A*xSquared))));

    if ( x > 0 )
    {
      return body;
    }

    return -body;
  }

  @Override
  SearchTreeNode createNode(ForwardDeadReckonInternalMachineState xiState,
                            int xiChoosingRole)
  {
    return new MCTSPrimeSearchTreeNode(tree, xiState, xiChoosingRole);
  }
}
