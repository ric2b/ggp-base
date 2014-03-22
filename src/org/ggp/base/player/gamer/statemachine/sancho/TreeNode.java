package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.File;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.player.gamer.statemachine.sancho.MCTSTree.MoveScoreInfo;
import org.ggp.base.player.gamer.statemachine.sancho.TreePath.TreePathElement;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;

class TreeNode
{
  public class TreeNodeRef
  {
    public TreeNode node;
    public int      seq;

    public TreeNodeRef(TreeNode node)
    {
      this.node = node;
      this.seq = node.seq;
    }
  }

  /**
   * The tree in which we're a node.
   */
  private final MCTSTree tree;

  private static final double           EPSILON             = 1e-6;

  int                                   seq                 = -1;
  int                                   numVisits           = 0;
  private int                           numUpdates          = 0;
  double[]                              averageScores;
  private double[]                      averageSquaredScores;
  private int[]                         numChoices;
  ForwardDeadReckonInternalMachineState state;
  int                                   decidingRoleIndex;
  private boolean                       isTerminal          = false;
  TreeEdge[]                            children            = null;
  Set<TreeNode>                         parents             = new HashSet<TreeNode>();
  int                                   trimmedChildren     = 0;
  private int                           sweepSeq;
  //private TreeNode sweepParent = null;
  boolean                               freed               = false;
  int                                   trimCount           = 0;
  private int                           leastLikelyWinner   = -1;
  private double                        leastLikelyRunnerUpValue;
  private int                           mostLikelyWinner    = -1;
  private double                        mostLikelyRunnerUpValue;
  boolean                               complete            = false;
  private boolean                       allChildrenComplete = false;

  TreeNode(MCTSTree tree, int numRoles) throws GoalDefinitionException
  {
    this.tree = tree;
    averageScores = new double[tree.numRoles];
    averageSquaredScores = new double[tree.numRoles];
  }

  private void correctParentsForCompletion(double values[])
  {
    //	Cannot do an a-priori correction of scores based on known child scores
    //	if heuristics are in use (at least not simply, so for now, just not)
    //if ( pieceStateMaps == null )
    {
      TreeNode primaryPathParent = null;
      int mostSelectedRouteCount = 0;

      for (TreeNode parent : parents)
      {
        if (parent.numUpdates > 0)
        {
          for (TreeEdge child : parent.children)
          {
            if (child.child.node == this)
            {
              if (child.numChildVisits > mostSelectedRouteCount)
              {
                mostSelectedRouteCount = child.numChildVisits;
                primaryPathParent = parent;
              }
              break;
            }
          }
        }
      }

      if (primaryPathParent != null && !primaryPathParent.complete)
      {
        double[] correctedAverageScores = new double[tree.numRoles];
        boolean propagate = true;


        //validateScoreVector(primaryPathParent.averageScores);

        double totalWeight = 0;

        for (int i = 0; i < tree.numRoles; i++)
        {
          correctedAverageScores[i] = 0;
        }

        for (TreeEdge edge : primaryPathParent.children)
        {
          if (edge.selectAs == edge && edge.child.seq == edge.child.node.seq)
          {
            double exploitationUct = primaryPathParent
                .exploitationUCT(edge, edge.child.node.decidingRoleIndex);

            double weight = (exploitationUct + 1 / Math
                .log(primaryPathParent.numVisits + 1)) *
                edge.child.node.numVisits + EPSILON;

            totalWeight += weight;
            for (int i = 0; i < tree.numRoles; i++)
            {
              correctedAverageScores[i] += weight *
                  edge.child.node.averageScores[i];
            }
          }
        }

        for (int i = 0; i < tree.numRoles; i++)
        {
          correctedAverageScores[i] /= totalWeight;
        }

        if (propagate)
        {
          primaryPathParent.correctParentsForCompletion(correctedAverageScores);
        }

        for (int i = 0; i < tree.numRoles; i++)
        {
          primaryPathParent.averageScores[i] = correctedAverageScores[i];
        }
        //validateScoreVector(primaryPathParent.averageScores);
      }
    }
  }

  private void validateCompletionValues(double[] values)
  {
    boolean matchesAll = true;
    boolean matchesDecider = false;

    if (children != null)
    {
      for (TreeEdge edge : children)
      {
        if (edge.selectAs == edge && edge.child.seq == edge.child.node.seq)
        {
          if (edge.child.node.complete)
          {
            if (edge.child.node.averageScores[decidingRoleIndex] == values[decidingRoleIndex])
            {
              matchesDecider = true;
            }
            else
            {
              matchesAll = false;
            }
          }
          else
          {
            matchesAll = false;
          }
        }
      }
    }

    if (!matchesAll && !matchesDecider)
    {
      System.out.println("Inexplicable completion!");
    }
  }

  private void markComplete(double[] values)
  {
    if (!complete)
    {
      //validateCompletionValues(values);
      //validateAll();
      if (numUpdates > 0)
      {
        //validateScoreVector(averageScores);
        correctParentsForCompletion(values);
        //validateScoreVector(averageScores);
      }

      for (int i = 0; i < tree.numRoles; i++)
      {
        averageScores[i] = values[i];
      }

      tree.numCompletedBranches++;
      complete = true;

      //System.out.println("Mark complete with score " + averageScore + (ourMove == null ? " (for opponent)" : " (for us)") + " in state: " + state);
      if (this == tree.root)
      {
        System.out.println("Mark root complete");
      }
      else
      {
        tree.completedNodeQueue.add(this);
      }

      if (trimmedChildren > 0)
      {
        //	Don't consider a complete node in the incomplete counts ever
        tree.numIncompleteNodes--;
        if (tree.numIncompleteNodes < 0)
        {
          System.out
          .println("Unexpected negative count of incomplete nodes");
        }
      }
      //validateAll();
    }
  }

  @SuppressWarnings("unused")
  void processCompletion()
  {
    //validateCompletionValues(averageScores);
    //System.out.println("Process completion of node seq: " + seq);
    //validateAll();
    //	Children can all be freed, at least from this parentage
    if (children != null && tree.freeCompletedNodeChildren)
    {
      for (TreeEdge edge : children)
      {
        TreeNodeRef cr = edge.child;
        if (cr.node.seq == cr.seq)
        {
          cr.node.freeFromAncestor(this);

          trimmedChildren++;

          cr.seq = -1;
        }
        else if (cr.seq != -1)
        {
          cr.seq = -1;
          trimmedChildren++;
        }
      }

      if (trimmedChildren == children.length)
      {
        children = null;
      }
      else
      {
        System.out.println("Impossible!");
      }
    }

    boolean decidingRoleWin = false;
    boolean mutualWin = true;

    for (int roleIndex = 0; roleIndex < tree.numRoles; roleIndex++)
    {
      if (averageScores[roleIndex] > 99.5)
      {
        if (roleIndex == decidingRoleIndex &&
            (!tree.gameCharacteristics.isSimultaneousMove || roleIndex == 0 || hasSiblinglessParents()))
        {
          decidingRoleWin = true;
        }
      }
      else
      {
        mutualWin = false;
      }
    }

    for (TreeNode parent : parents)
    {
      if (decidingRoleWin && !mutualWin)
      {
        // Win for whoever just moved after they got to choose so parent node is also decided
        parent.markComplete(averageScores);

        //	Force win in this state means a very likely good move in sibling states
        //	so note that their selection probabilities should be increased
        //markCousinsAsPriorityToSelect();
      }
      else
      {
        //	If all children are complete then the parent is - give it a chance to
        //	decide
        parent.checkChildCompletion(true);
      }
    }
    //validateAll();
  }

  private void freeFromAncestor(TreeNode ancestor)
  {
    //if ( sweepParent == ancestor && sweepSeq == sweepInstance)
    //{
    //	System.out.println("Removing sweep parent");
    //}
    parents.remove(ancestor);

    if (parents.size() == 0)
    {
      if (children != null)
      {
        for (TreeEdge edge : children)
        {
          if (edge.child.node.seq == edge.child.seq)
          {
            edge.child.node.freeFromAncestor(this);
          }
        }
      }

      freeNode();
    }
  }

  private boolean hasSiblings()
  {
    for (TreeNode parent : parents)
    {
      int numChildren = 0;

      if (parent.children != null)
      {
        for (TreeEdge edge : parent.children)
        {
          if (edge.selectAs == edge)
          {
            numChildren++;
          }
        }
      }

      if (numChildren > 1)
      {
        return true;
      }
    }

    return false;
  }

  private boolean hasSiblinglessParents()
  {
    for (TreeNode parent : parents)
    {
      if (parent == tree.root)
      {
        return false;
      }

      for (TreeNode grandParent : parent.parents)
      {
        if (grandParent.children.length > 1)
        {
          return false;
        }
      }
    }

    return true;
  }

  private boolean allNephewsComplete()
  {
    for (TreeNode parent : parents)
    {
      for (TreeEdge edge : parent.children)
      {
        TreeNode child = edge.child.node;

        if (edge.selectAs == edge)
        {
          if (edge.child.seq == child.seq)
          {
            if (!child.complete)
            {
              if (child.children != null)
              {
                for (TreeEdge nephewEdge : child.children)
                {
                  TreeNode nephew = nephewEdge.child.node;

                  if (nephewEdge.child.seq != nephew.seq || !nephew.complete)
                  {
                    return false;
                  }
                }
              }
              else
              {
                return false;
              }
            }
          }
          else
          {
            return false;
          }
        }
      }
    }

    return true;
  }

  private void checkSiblingCompletion(double[] floorDeciderScore)
  {
    for (TreeNode parent : parents)
    {
      for (TreeEdge edge : parent.children)
      {
        TreeNode child = edge.child.node;

        if (child != this && edge.child.seq == child.seq &&
            child.children != null && !child.complete)
        {
          child.checkChildCompletion(false);
        }
      }
    }
  }

  private boolean isBestMoveInAllUncles(Set<Move> moves, int roleIndex)
  {
    for (TreeNode parent : parents)
    {
      for (TreeEdge edge : parent.children)
      {
        TreeNode child = edge.child.node;

        if (child != this)
        {
          if (edge.child.seq != child.seq ||
              (child.children == null && !child.complete))
          {
            return false;
          }

          if (!child.complete)
          {
            double bestOtherMoveScore = 0;
            double thisMoveScore = -Double.MAX_VALUE;
            for (TreeEdge nephewEdge : child.children)
            {
              TreeNode nephew = nephewEdge.child.node;

              if (nephewEdge.child.seq == nephew.seq)
              {
                if (moves
                    .contains(nephewEdge.jointPartialMove[nephew.decidingRoleIndex].move))
                {
                  if (nephew.averageScores[roleIndex] > thisMoveScore)
                  {
                    thisMoveScore = nephew.averageScores[roleIndex];
                  }
                }
                else
                {
                  if (nephew.averageScores[roleIndex] > bestOtherMoveScore)
                  {
                    bestOtherMoveScore = nephew.averageScores[roleIndex];
                  }
                }
              }
            }

            if (bestOtherMoveScore > thisMoveScore &&
                thisMoveScore != -Double.MAX_VALUE)
            {
              return false;
            }
          }
          else if (child.averageScores[roleIndex] < 99.5)
          {
            return false;
          }
        }
      }
    }

    return true;
  }

  private double[] worstCompleteCousinValues(Move move, int roleIndex)
  {
    double[] result = null;

    for (TreeNode parent : parents)
    {
      for (TreeEdge edge : parent.children)
      {
        TreeNode child = edge.child.node;

        if (edge.selectAs == edge)
        {
          if (edge.child.seq != child.seq ||
              (child.children == null && !child.complete))
          {
            return null;
          }

          if (!child.complete)
          {
            for (TreeEdge nephewEdge : child.children)
            {
              TreeNode nephew = nephewEdge.child.node;

              if (nephewEdge.child.seq == nephew.seq &&
                  move == nephewEdge.jointPartialMove[nephew.decidingRoleIndex].move)
              {
                if (!nephew.complete)
                {
                  return null;
                }
                if (result == null ||
                    nephew.averageScores[roleIndex] < result[roleIndex])
                {
                  result = nephew.averageScores;
                }
              }
            }
          }
          else if (result == null ||
              child.averageScores[roleIndex] < result[roleIndex])
          {
            result = child.averageScores;
          }
        }
      }
    }

    return result;
  }

  private void checkSiblingCompletion(TreeEdge withRespectTo)
  {
    for (TreeNode parent : parents)
    {
      for (TreeEdge edge : parent.children)
      {
        TreeNode child = edge.child.node;

        if (child != this && edge.child.seq == child.seq &&
            child.children != null && !child.complete)
        {
          child.checkChildCompletion(false);
        }
      }
    }
  }

  @SuppressWarnings("null")
  private void checkChildCompletion(boolean checkConsequentialSiblingCompletion)
  {
    boolean allImmediateChildrenComplete = true;
    double bestValue = -1000;
    double[] bestValues = null;
    double[] averageValues = new double[tree.numRoles];
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;
    boolean decidingRoleWin = false;
    double[] worstDeciderScore = null;
    double[] floorDeciderScore = null;

    int numUniqueChildren = 0;

    //			double bestIncompleteDecidingRoleVal = 0;
    //			if ( isSimultaneousMove )
      //			{
      //				for(TreeEdge edge : children)
        //				{
        //					TreeNodeRef cr = edge.child;
        //					if ( cr.node.seq == cr.seq && edge.selectAs == edge && !cr.node.complete )
          //					{
    //						if ( cr.node.averageScores[roleIndex] > bestIncompleteDecidingRoleVal )
    //						{
    //							bestIncompleteDecidingRoleVal = cr.node.averageScores[roleIndex];
    //						}
    //					}
    //				}
    //			}

    for (TreeEdge edge : children)
    {
      TreeNodeRef cr = edge.child;
      if (cr.node.seq == cr.seq)
      {
        if (edge.selectAs == edge)
        {
          numUniqueChildren++;

          if (!cr.node.complete)
          {
            allImmediateChildrenComplete = false;
          }
          else
          {
            if (worstDeciderScore == null ||
                cr.node.averageScores[roleIndex] < worstDeciderScore[roleIndex])
            {
              worstDeciderScore = cr.node.averageScores;
            }

            if (cr.node.averageScores[roleIndex] >= bestValue)
            {
              bestValue = cr.node.averageScores[roleIndex];
              bestValues = cr.node.averageScores;

              if (bestValue > 99.5)
              {
                //	Win for deciding role which they will choose unless it is also
                //	a mutual win
                boolean mutualWin = true;

                for (int i = 0; i < tree.numRoles; i++)
                {
                  if (cr.node.averageScores[i] < 99.5)
                  {
                    mutualWin = false;
                    break;
                  }
                }

                if (!decidingRoleWin)
                {
                  decidingRoleWin |= !mutualWin;

                  if (decidingRoleWin && tree.gameCharacteristics.isSimultaneousMove)
                  {
                    //	Only complete on this basis if this move is our choice (complete info)
                    //	or wins in ALL cousin states also
                    if (roleIndex != 0 && hasSiblings())
                    {
                      Set<Move> equivalentMoves = new HashSet<Move>();
                      for (TreeEdge siblingEdge : children)
                      {
                        if (siblingEdge.selectAs == edge)
                        {
                          equivalentMoves
                          .add(siblingEdge.jointPartialMove[roleIndex].move);
                        }
                      }
                      if (!isBestMoveInAllUncles(equivalentMoves, roleIndex))
                      {
                        decidingRoleWin = false;
                      }
                      else
                      {
                        if (checkConsequentialSiblingCompletion)
                        {
                          checkSiblingCompletion(edge);
                        }
                      }
                    }
                  }
                }
              }
            }

            if (tree.gameCharacteristics.isSimultaneousMove &&
                !decidingRoleWin &&
                roleIndex != 0 &&
                (floorDeciderScore == null || floorDeciderScore[roleIndex] < edge.child.node.averageScores[roleIndex]))
            {
              //	Find the highest supported floor score for any of the moves equivalent to this one
              double[] worstCousinValues = null;
              for (TreeEdge siblingEdge : children)
              {
                if (siblingEdge.selectAs == edge)
                {
                  double[] moveFloor = worstCompleteCousinValues(siblingEdge.jointPartialMove[roleIndex].move,
                                                                 roleIndex);

                  if (moveFloor != null)
                  {
                    if (worstCousinValues == null ||
                        worstCousinValues[roleIndex] < moveFloor[roleIndex])
                    {
                      worstCousinValues = moveFloor;
                    }
                  }
                }
              }

              if (worstCousinValues != null &&
                  (floorDeciderScore == null || floorDeciderScore[roleIndex] < worstCousinValues[roleIndex]))
              {
                floorDeciderScore = worstCousinValues;
              }
            }
          }

          for (int i = 0; i < tree.numRoles; i++)
          {
            averageValues[i] += cr.node.averageScores[i];
          }
        }
      }
      else
      {
        allImmediateChildrenComplete = false;
      }
    }

    for (int i = 0; i < tree.numRoles; i++)
    {
      averageValues[i] /= numUniqueChildren;
    }

    if (allImmediateChildrenComplete && !decidingRoleWin &&
        tree.gameCharacteristics.isSimultaneousMove && decidingRoleIndex == 0)
    {
      allChildrenComplete = true;

      //	If the best we can do from this node is no better than the supported floor we
      //	don't require all nephews to be complete to complete this node at the floor
      if (!hasSiblings() ||
          (floorDeciderScore != null && floorDeciderScore[roleIndex] +
          EPSILON >= bestValues[roleIndex]))
      {
        //	There was only one opponent choice so this is not after all
        //	incomplete information, so complete with the best choice for
        //	the decider
        decidingRoleWin = true;
      }
      else
      {
        //	To auto complete with simultaneous turn and no deciding role win
        //	we require that all nephews be complete or that all alternatives
        //	are anyway equivalent
        boolean allNephewsComplete = allNephewsComplete();

        for (int i = 0; i < tree.numRoles; i++)
        {
          if (Math.abs(averageValues[i] - bestValues[i]) > EPSILON)
          {
            allImmediateChildrenComplete = allNephewsComplete;

            break;
          }
        }
      }

      if (allImmediateChildrenComplete &&
          checkConsequentialSiblingCompletion)
      {
        checkSiblingCompletion(floorDeciderScore);
      }
    }

    if (allImmediateChildrenComplete || decidingRoleWin)
    {
      tree.numCompletionsProcessed++;

      //	Opponent's choice which child to take, so take their
      //	best value and crystalize as our value.   However, if it's simultaneous
      //	move complete with the average score since
      //	opponents cannot make the pessimal (for us) choice reliably
      if (tree.gameCharacteristics.isSimultaneousMove && !decidingRoleWin && decidingRoleIndex == 0)
      {
        double[] blendedCompletionScore = new double[tree.numRoles];
        //	This feels a bit of a hack, but it seems to work - in general when the outcome
        //	is complete for all choices but varies we err on the pessimistic side.
        //	However, if we just choose the worst result then a move with many bad results
        //	looks the same as one with a single bad result (with respect to opponent choices),
        //	so shade the score up slightly by the average (the 100:1 ratio is arbitrary)
        //	Note that just using the average also doesn't work, and will cause massive
        //	over-optimism.
        for (int i = 0; i < tree.numRoles; i++)
        {
          blendedCompletionScore[i] = (worstDeciderScore[i] * 100 + averageValues[i]) / 101;
        }
        //	If a move provides a better-than-worst case in all uncles it provides a support
        //	floor the the worst that we can do with perfect play, so use that if its larger than
        //	what we would otherwise use
        if (floorDeciderScore != null &&
            floorDeciderScore[roleIndex] > worstDeciderScore[roleIndex])
        {
          blendedCompletionScore = floorDeciderScore;
        }
        markComplete(blendedCompletionScore);
        //markComplete(averageValues);
      }
      else
      {
        markComplete(bestValues);
      }
    }

    mostLikelyWinner = -1;
  }

  public void reset(boolean freed)
  {
    numVisits = 0;
    numUpdates = 0;
    if (averageScores != null)
    {
      if (freed)
      {
        averageScores = null;
        averageSquaredScores = null;
      }
      else
      {
        for (int i = 0; i < averageScores.length; i++)
        {
          averageScores[i] = 0;
          averageSquaredScores[i] = 0;
        }
      }
    }
    else if (!freed)
    {
      averageScores = new double[tree.numRoles];
      averageSquaredScores = new double[tree.numRoles];
    }
    state = null;
    isTerminal = false;
    children = null;
    parents.clear();
    trimmedChildren = 0;
    this.freed = freed;
    trimCount = 0;
    leastLikelyWinner = -1;
    mostLikelyWinner = -1;
    complete = false;
    allChildrenComplete = false;
    numChoices = null;
    seq = -1;
  }

  private TreeNodeRef getRef()
  {
    return new TreeNodeRef(this);
  }

  void validate(boolean recursive)
  {
    if (children != null)
    {
      int missingChildren = 0;
      for (TreeEdge edge : children)
      {
        if (edge != null)
        {
          TreeNodeRef cr = edge.child;
          if (cr.node.seq == cr.seq)
          {
            if (!cr.node.parents.contains(this))
            {
              System.out.println("Missing parent link");
            }
            if (cr.node.complete &&
                cr.node.averageScores[decidingRoleIndex] > 99.5 &&
                !complete && !tree.completedNodeQueue.contains(cr.node))
            {
              System.out.println("Completeness constraint violation");
            }
            if ((cr.node.decidingRoleIndex) == decidingRoleIndex &&
                !tree.gameCharacteristics.isPuzzle)
            {
              System.out.println("Descendant type error");
            }

            if (recursive)
            {
              cr.node.validate(true);
            }
          }
          else
          {
            missingChildren++;
          }
        }
      }

      if (missingChildren != trimmedChildren)
      {
        System.out.println("Trimmed child count incorrect");
      }
    }

    if (parents.size() > 0)
    {
      int numInwardVisits = 0;

      for (TreeNode parent : parents)
      {
        for (TreeEdge edge : parent.children)
        {
          if (edge.child.node == this)
          {
            numInwardVisits += edge.numChildVisits;
            break;
          }
        }
      }

      if (numInwardVisits > numVisits)
      {
        System.out.println("Linkage counts do not add up");
      }
    }
  }

  private void markTreeForSweep()
  {
    if (sweepSeq != tree.sweepInstance)
    {
      sweepSeq = tree.sweepInstance;
      if (children != null)
      {
        for (TreeEdge edge : children)
        {
          if (edge.child.node.seq == edge.child.seq)
          {
            //if ( !cr.node.parents.contains(this))
            //{
            //	System.out.println("Child relation inverse missing");
            //}
            //cr.node.sweepParent = this;
            edge.child.node.markTreeForSweep();
          }
        }
      }
    }
  }

  private void freeNode()
  {
    ProfileSection methodSection = new ProfileSection("TreeNode.freeNode");
    try
    {
      //validateAll();

      if (freed)
      {
        System.out.println("Freeing already free node!");
      }
      if (decidingRoleIndex == tree.numRoles - 1)
      {
        //if ( positions.get(state) != this )
        //{
        //	System.out.println("Position index does not point to freed node");
        //}
        tree.positions.remove(state);
      }
      //if ( positions.containsValue(this))
      //{
      //	System.out.println("Node still referenced!");
      //}

      tree.nodeMoveWeightsCache.remove(this);

      if (trimmedChildren > 0 && !complete)
      {
        tree.numIncompleteNodes--;
        if (tree.numIncompleteNodes < 0)
        {
          System.out
          .println("Unexpected negative count of incomplete nodes");
        }
      }
      if (complete)
      {
        tree.numCompletedBranches--;
      }

      if (children != null)
      {
        for (TreeEdge edge : children)
        {
          if (edge != null)
          {
            if (edge.child.node.seq == edge.child.seq)
            {
              if (edge.child.node.parents.size() != 0)
              {
                int numRemainingParents = edge.child.node.parents.size();
                //if ( cr.node.sweepParent == this && sweepSeq == sweepInstance)
                //{
                //	System.out.println("Removing sweep parent");
                //}
                edge.child.node.parents.remove(this);
                if (numRemainingParents == 0)
                {
                  System.out.println("Orphaned child node");
                }
                else
                {
                  //	Best estimate of likely paths to the child node given removal of parent
                  //edge.child.node.numVisits = (edge.child.node.numVisits*numRemainingParents + numRemainingParents)/(numRemainingParents+1);
                }
              }
            }
          }
        }
      }

      //System.out.println("    Freeing (" + ourIndex + "): " + state);
      tree.numFreedTreeNodes++;
      seq = -2; //	Must be negative and distinct from -1, the null ref seq value
      tree.freeList.add(this);
      freed = true;

      tree.numUsedNodes--;
      //validateAll();
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public void freeAllBut(TreeNode descendant)
  {
    if (descendant != null)
    {
      System.out
      .println("Free all but rooted in state: " + descendant.state);
      tree.sweepInstance++;

      descendant.markTreeForSweep();
      descendant.parents.clear(); //	Do this here to allow generic orphan checking in node freeing
      //	without tripping over this special case
    }

    if (descendant == this || sweepSeq == tree.sweepInstance)
    {
      //System.out.println("    Leaving: " + state);
      return;
    }

    if (children != null)
    {
      for (TreeEdge edge : children)
      {
        if (edge.child.node.seq == edge.child.seq)
        {
          edge.child.node.freeAllBut(null);
        }
      }
    }

    freeNode();
  }

  public TreeNode findNode(ForwardDeadReckonInternalMachineState targetState,
                           int maxDepth)
  {
    if (state.equals(targetState) && decidingRoleIndex == tree.numRoles - 1)
    {
      return this;
    }
    else if (maxDepth == 0)
    {
      return null;
    }

    if (children != null)
    {
      for (TreeEdge edge : children)
      {
        TreeNodeRef cr = edge.child;
        if (cr.node.seq == cr.seq)
        {
          TreeNode childResult = cr.node.findNode(targetState, maxDepth - 1);
          if (childResult != null)
          {
            return childResult;
          }
        }
      }
    }

    return null;
  }

  public void disposeLeastLikelyNode()
  {
    ProfileSection methodSection = new ProfileSection("TreeNode.disposeLeastLikelyNode");
    try
    {
      TreeNode leastLikely = selectLeastLikelyNode(null, 0);

      //leastLikely.adjustDescendantCounts(-1);
      leastLikely.freeNode();
      //validateAll();
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public TreeNode selectLeastLikelyNode(TreeEdge from, int depth)
  {
    int selectedIndex = -1;
    double bestValue = -Double.MAX_VALUE;

    //	Find the role this node is choosing for
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

    tree.cousinMovesCachedFor = null;

    //validateAll();
    //System.out.println("Select LEAST in " + state);
    if (freed)
    {
      System.out.println("Encountered freed node in tree walk");
    }
    if (children != null)
    {
      if (children.length == 1)
      {
        TreeEdge edge = children[0];

        if (edge.child.node.seq == edge.child.seq)
        {
          selectedIndex = 0;
        }
      }
      else
      {
        if (leastLikelyWinner != -1)
        {
          TreeEdge edge = children[leastLikelyWinner];
          TreeNode c = edge.child.node;
          if (edge.child.seq == c.seq)
          {
            double uctValue;
            if (edge.numChildVisits == 0)
            {
              uctValue = -1000;
            }
            else
            {
              uctValue = -explorationUCT(numVisits,
                                         edge.numChildVisits,
                                         roleIndex) -
                                         exploitationUCT(edge, roleIndex);
              //uctValue = -c.averageScore/100 - Math.sqrt(Math.log(Math.max(numVisits,numChildVisits[leastLikelyWinner])+1) / numChildVisits[leastLikelyWinner]);
            }
            uctValue /= Math.log(Math.max(1, c.numVisits + 2 - c.trimCount)); //	utcVal is negative so this makes larger subtrees score higher (less negative)

            if (uctValue >= leastLikelyRunnerUpValue)
            {
              selectedIndex = leastLikelyWinner;
            }
          }
        }

        if (selectedIndex == -1)
        {
          leastLikelyRunnerUpValue = -Double.MAX_VALUE;
          for (int i = 0; i < children.length; i++)
          {
            TreeEdge edge = children[i];
            TreeNodeRef cr = edge.child;
            if (cr != null)
            {
              TreeNode c = cr.node;
              if (c.seq != cr.seq)
              {
                if (cr.seq != -1)
                {
                  if (trimmedChildren++ == 0)
                  {
                    tree.numIncompleteNodes++;
                  }
                  cr.seq = -1;
                }
              }
              else
              {
                if (c.freed)
                {
                  System.out
                  .println("Encountered freed child node in tree walk");
                }
                double uctValue;
                if (edge.numChildVisits == 0)
                {
                  uctValue = -1000;
                }
                //else if ( c.complete )
                //{
                //	Resist clearing away complete nodes as they potentially
                //	represent a lot of work
                //	uctValue = -500;
                //}
                else
                {
                  uctValue = -explorationUCT(numVisits,
                                             edge.numChildVisits,
                                             roleIndex) -
                                             exploitationUCT(edge, roleIndex);
                  //uctValue = -c.averageScore/100 - Math.sqrt(Math.log(Math.max(numVisits,numChildVisits[i])+1) / numChildVisits[i]);
                }
                uctValue /= Math.log(c.numVisits + 2); //	utcVal is negative so this makes larger subtrees score higher (less negative)

                //if ( c.isLeaf() )
                //{
                //	uctValue += uctValue/(depth+1);
                //}

                //System.out.println("  child score of " + uctValue + " in state "+ c.state);
                if (uctValue > bestValue)
                {
                  selectedIndex = i;
                  if (bestValue != -Double.MAX_VALUE)
                  {
                    leastLikelyRunnerUpValue = bestValue;
                  }
                  bestValue = uctValue;
                }
              }
            }
          }
        }
      }
    }

    //validateAll();
    if (selectedIndex != -1)
    {
      leastLikelyWinner = selectedIndex;
      trimCount++;
      //System.out.println("  selected: " + selected.state);
      return children[selectedIndex].child.node
          .selectLeastLikelyNode(children[selectedIndex], depth + 1);
    }

    if (depth < 2)
      System.out.println("Selected unlikely node at depth " + depth);
    return this;
  }

  public void selectAction()
      throws MoveDefinitionException, TransitionDefinitionException,
      GoalDefinitionException, InterruptedException
      {
    ProfileSection methodSection = new ProfileSection("TreeNode.selectAction");
    try
    {
      MoveWeightsCollection moveWeights = (tree.moveActionHistoryBias != 0 ? new MoveWeightsCollection(tree.numRoles)
      : null);

      //validateAll();
      tree.completedNodeQueue.clear();

      //List<TreeNode> visited = new LinkedList<TreeNode>();
      TreePath visited = new TreePath(tree);
      TreeNode cur = this;
      TreePathElement selected = null;
      //visited.add(this);
      while (!cur.isUnexpanded())
      {
        selected = cur.select(visited,
                              selected == null ? null : selected.getEdge(),
                              moveWeights);

        cur = selected.getChildNode();
        //visited.add(cur);
        visited.push(selected);
      }

      TreeNode newNode;
      if (!cur.complete)
      {
        //	Expand for each role so we're back to our-move as we always rollout after joint moves
        cur.expand(selected == null ? null : selected.getEdge());

        if (!cur.complete)
        {
          selected = cur
              .select(visited,
                      selected == null ? null : selected.getEdge(),
                      moveWeights);
          newNode = selected.getChildNode();
          //visited.add(newNode);
          visited.push(selected);
          while (newNode.decidingRoleIndex != tree.numRoles - 1 &&
              !newNode.complete)
          {
            newNode.expand(selected.getEdge());
            if (!newNode.complete)
            {
              selected = newNode.select(visited, selected.getEdge(), moveWeights);
              newNode = selected.getChildNode();
              //visited.add(newNode);
              visited.push(selected);
            }
          }
        }
        else
        {
          newNode = cur;
        }
      }
      else
      {
        //	If we've selected a terminal node we still do a pseudo-rollout
        //	from it so its value gets a weight increase via back propagation
        newNode = cur;
      }

      //	Add a pseudo-edge that represents the link into the unexplored part of the tree
      //visited.push(null);
      //validateAll();
      //System.out.println("Rollout from: " + newNode.state);
      RolloutRequest rollout = newNode.rollOut(visited);
      if (rollout != null)
      {
        newNode.updateStats(rollout.averageScores,
                            rollout.averageSquaredScores,
                            tree.rolloutSampleSize,
                            visited,
                            true);
      }
      else
      {
        //for(TreeNode node : visited)
        //{
        //	node.validate(false);
        //}
        newNode.updateVisitCounts(tree.rolloutSampleSize, visited);
      }
      //validateAll();
    }
    finally
    {
      methodSection.exitScope();
    }
      }

  public void expand(TreeEdge from)
      throws MoveDefinitionException, TransitionDefinitionException,
      GoalDefinitionException
      {
    ProfileSection methodSection = new ProfileSection("TreeNode.expand");
    try
    {
      if (children == null || trimmedChildren > 0)
      {
        //	Find the role this node is choosing for
        int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;
        Role choosingRole = tree.roleOrdering.roleIndexToRole(roleIndex);
        //validateAll();

        //System.out.println("Expand our moves from state: " + state);
        ForwardDeadReckonLegalMoveSet moves = tree.underlyingStateMachine
            .getLegalMoves(state);
        List<ForwardDeadReckonLegalMoveInfo> moveInfos = new LinkedList<ForwardDeadReckonLegalMoveInfo>();

        for (ForwardDeadReckonLegalMoveInfo move : moves
            .getContents(choosingRole))
        {
          moveInfos.add(move);
        }
        TreeEdge[] newChildren = new TreeEdge[moves
                                              .getContentSize(choosingRole)];

        int index = 0;
        if (children != null)
        {
          for (TreeEdge edge : children)
          {
            TreeNode child = edge.child.node;
            if (edge.child.seq == child.seq)
            {
              moveInfos.remove(edge.jointPartialMove[roleIndex]);
              newChildren[index] = edge;
              index++;
            }
          }
        }

        while (index < newChildren.length)
        {
          TreeEdge newEdge = new TreeEdge(tree.numRoles);
          ForwardDeadReckonInternalMachineState newState = null;

          for (int i = 0; i < roleIndex; i++)
          {
            newEdge.jointPartialMove[i] = from.jointPartialMove[i];
          }
          newEdge.jointPartialMove[roleIndex] = moveInfos.remove(0);
          if (roleIndex == tree.numRoles - 1)
          {
            newState = tree.underlyingStateMachine
                .getNextState(state, newEdge.jointPartialMove);
          }

          newEdge.child = tree.allocateNode(tree.underlyingStateMachine,
                                            newState,
                                            this).getRef();
          newEdge.selectAs = newEdge;

          //	Check for multiple moves that all transition to the same state
          for (int i = 0; i < index; i++)
          {
            if (newChildren[i] != null &&
                newChildren[i].child.node == newEdge.child.node)
            {
              newEdge.selectAs = newChildren[i];
              break;
            }
          }

          newChildren[index] = newEdge;
          TreeNode newChild = newEdge.child.node;

          newChild.decidingRoleIndex = roleIndex;

          if (numChoices == null)
          {
            numChoices = new int[tree.numRoles];
            for (int i = 0; i < tree.numRoles; i++)
            {
              numChoices[i] = 0;
            }
          }

          if (newState == null)
          {
            newChild.state = state;
            newChild.numChoices = numChoices;
          }
          else
          {
            newChild.numChoices = new int[tree.numRoles];

            if (tree.underlyingStateMachine.isTerminal(newState))
            {
              newChild.isTerminal = true;

              for (int i = 0; i < tree.numRoles; i++)
              {
                newChild.averageScores[i] = tree.underlyingStateMachine
                    .getGoal(tree.roleOrdering.roleIndexToRole(i));
                newChild.averageSquaredScores[i] = 0;
              }

              //	Add win bonus
              for (int i = 0; i < tree.numRoles; i++)
              {
                double iScore = newChild.averageScores[i];
                tree.bonusBuffer[i] = 0;

                for (int j = 0; j < tree.numRoles; j++)
                {
                  if (j != i)
                  {
                    double jScore = newChild.averageScores[j];

                    if (iScore >= jScore)
                    {
                      double bonus = tree.competitivenessBonus;

                      if (iScore > jScore)
                      {
                        bonus *= 2;
                      }

                      tree.bonusBuffer[i] += bonus;
                    }
                  }
                }
              }

              for (int i = 0; i < tree.numRoles; i++)
              {
                newChild.averageScores[i] = ((newChild.averageScores[i] + tree.bonusBuffer[i]) * 100) /
                    (100 + 2 * (tree.numRoles - 1) *
                        tree.competitivenessBonus);
              }
            }
            else
            {
              for (int i = 0; i < tree.numRoles; i++)
              {
                int num = tree.underlyingStateMachine
                    .getLegalMoves(newChild.state)
                    .getContentSize(tree.roleOrdering.roleIndexToRole(i));
                if (num > 1)
                {
                  newChild.numChoices[i] = num;
                }
                else
                {
                  newChild.numChoices[i] = numChoices[i];
                }
              }
            }
          }

          if (newChild.numVisits == 0 && tree.heuristicProvider.getSampleWeight() > 0 &&
              !newChild.isTerminal)
          {
            double[] heuristicScores = tree.heuristicProvider.heuristicStateValue(newChild.state,
                                                                this);
            double heuristicSquaredDeviation = 0;

            //validateScoreVector(heuristicScores);

            for (int i = 0; i < tree.numRoles; i++)
            {
              newChild.averageScores[i] = heuristicScores[i];
              heuristicSquaredDeviation += (tree.root.averageScores[i] - heuristicScores[i]) *
                  (tree.root.averageScores[i] - heuristicScores[i]);
            }

            if (heuristicSquaredDeviation > 0.01 && tree.root.numVisits > 50)
            {
              newChild.numUpdates = tree.heuristicProvider.getSampleWeight();
              newChild.numVisits = tree.heuristicProvider.getSampleWeight();
            }
          }

          index++;
        }

        children = newChildren;

        //validateAll();

        if (trimmedChildren > 0)
        {
          trimmedChildren = 0; //	This is a fresh expansion entirely can go back to full UCT
          tree.numIncompleteNodes--;
          if (tree.numIncompleteNodes < 0)
          {
            System.out
            .println("Unexpected negative count of incomplete nodes");
          }
        }

        boolean completeChildFound = false;

        for (TreeEdge edge : children)
        {
          TreeNodeRef cr = edge.child;
          if (cr.node.seq == cr.seq)
          {
            if (cr.node.isTerminal)
            {
              cr.node.markComplete(cr.node.averageScores);
              completeChildFound = true;
            }
            if (cr.node.complete)
            {
              completeChildFound = true;
            }
          }
        }

        if (completeChildFound && !complete)
        {
          checkChildCompletion(true);
        }
        //validateAll();
      }
    }
    finally
    {
      methodSection.exitScope();
    }
      }

  private void validateScoreVector(double[] scores)
  {
    double total = 0;

    for (int i = 0; i < tree.numRoles; i++)
    {
      total += scores[i];
    }

    if (total > 0 && Math.abs(total - 100) > EPSILON)
    {
      System.out.println("Bad score vector");
    }

    if (total > 0 && children != null)
    {
      total = 0;
      int visitTotal = 0;

      for (TreeEdge edge : children)
      {
        if (edge == edge.selectAs)
        {
          total += edge.child.node.averageScores[0] * edge.numChildVisits;
          visitTotal += edge.numChildVisits;
        }
      }

      if (visitTotal > 200 &&
          Math.abs(averageScores[0] - total / visitTotal) > 10)
      {
        System.out.println("Parent stats do not match chikdren");
      }
    }
  }

  private double explorationUCT(int effectiveTotalVists,
                                int numChildVisits,
                                int roleIndex)
  {
    //	When we propagate adjustments due to completion we do not also adjust the variance contribution
    //	so this can result in 'impossibly' low (aka negative) variance - take a lower bound of 0
    double varianceBound = Math.max(0, averageSquaredScores[roleIndex] -
                                    averageScores[roleIndex] *
                                    averageScores[roleIndex]) /
                                    10000 +
                                    Math.sqrt(2 *
                                              Math.log(Math.max(effectiveTotalVists,
                                                                numChildVisits) + 1) /
                                                                numChildVisits);
    return tree.explorationBias *
        Math.sqrt(2 *
                  Math.min(0.5, varianceBound) *
                  Math.log(Math.max(effectiveTotalVists, numChildVisits) + 1) /
                  numChildVisits) / tree.roleRationality[roleIndex];
  }

  private void addMoveWeightsToAncestors(Move move,
                                         int roleIndex,
                                         double weight)
  {
    TreeNode primaryPathParent = null;
    int mostSelectedRouteCount = 0;

    for (TreeNode parent : parents)
    {
      if (parent.numUpdates > 0)
      {
        for (TreeEdge child : parent.children)
        {
          if (child.child.node == this)
          {
            if (child.numChildVisits > mostSelectedRouteCount)
            {
              mostSelectedRouteCount = child.numChildVisits;
              primaryPathParent = parent;
            }
            break;
          }
        }
      }
    }

    if (primaryPathParent != null)
    {
      MoveWeightsCollection weights = tree.nodeMoveWeightsCache.get(primaryPathParent);

      if (weights == null)
      {
        weights = new MoveWeightsCollection(tree.numRoles);
        tree.nodeMoveWeightsCache.put(primaryPathParent, weights);
      }

      weights.addMove(move, roleIndex, weight);

      primaryPathParent.mostLikelyWinner = -1;
      primaryPathParent
      .addMoveWeightsToAncestors(move,
                                 roleIndex,
                                 weight * MoveWeightsCollection.decayRate);
    }
  }

  private double getAverageCousinMoveValue(TreeEdge relativeTo, int roleIndex)
  {
    if (relativeTo.child.node.decidingRoleIndex == 0)
    {
      return relativeTo.child.node.averageScores[roleIndex] / 100;
    }
    else if (tree.cousinMovesCachedFor == null ||
        tree.cousinMovesCachedFor.node != this ||
        tree.cousinMovesCachedFor.seq != seq)
    {
      tree.cousinMovesCachedFor = getRef();
      tree.cousinMoveCache.clear();

      for (TreeNode parent : parents)
      {
        for (TreeEdge edge : parent.children)
        {
          TreeNode child = edge.child.node;

          if (edge.child.seq == child.seq && child.children != null)
          {
            double thisSampleWeight = 0.1 + child.averageScores[child.decidingRoleIndex];//(child.averageScores[child.decidingRoleIndex]*(child.numVisits+1) + 50*Math.log(child.numVisits+1))/(child.numVisits+1);

            for (TreeEdge nephewEdge : child.children)
            {
              TreeNode nephew = nephewEdge.child.node;

              if (nephewEdge.child.seq == nephew.seq)
              {
                Move move = nephewEdge.jointPartialMove[relativeTo.child.node.decidingRoleIndex].move;
                MoveScoreInfo accumulatedMoveInfo = tree.cousinMoveCache
                    .get(move);
                if (accumulatedMoveInfo == null)
                {
                  accumulatedMoveInfo = tree.new MoveScoreInfo();
                  tree.cousinMoveCache.put(move, accumulatedMoveInfo);
                }

                if (thisSampleWeight != 0)
                {
                  accumulatedMoveInfo.averageScore = (accumulatedMoveInfo.averageScore *
                      accumulatedMoveInfo.sampleWeight + thisSampleWeight *
                      nephew.averageScores[roleIndex]) /
                      (accumulatedMoveInfo.sampleWeight + thisSampleWeight);
                  accumulatedMoveInfo.sampleWeight += thisSampleWeight;
                }
              }
            }
          }
        }
      }
    }

    MoveScoreInfo accumulatedMoveInfo = tree.cousinMoveCache
        .get(relativeTo.jointPartialMove[relativeTo.child.node.decidingRoleIndex].move);
    if (accumulatedMoveInfo == null)
    {
      System.out
      .println("No newphews found for search move including own child!");
      tree.cousinMovesCachedFor = null;
      //getAverageCousinMoveValue(relativeTo);
      return relativeTo.child.node.averageScores[roleIndex] / 100;
    }
    return accumulatedMoveInfo.averageScore / 100;
  }

  private double exploitationUCT(TreeEdge inboundEdge, int roleIndex)
  {
    //double stdDeviationMeasure = Math.sqrt((averageSquaredScore - averageScore*averageScore)/10000) - 0.25;
    //double stdDeviationContribution = stdDeviationMeasure - 2*averageScore*stdDeviationMeasure/100;
    //final double alpha = 0.5;

    //if ( isMultiPlayer && ourMove == null )
    //{
    //	For multi-player games inject noise onto enemy scores of magnitude proportional
    //	to their observed non-correlation of terminal position scores
    //	return Math.min(0,  Math.max(averageScore + r.nextInt(multiRoleAverageScoreDiff*2) - multiRoleAverageScoreDiff,100))/100;
    //}
    //else
    if (tree.gameCharacteristics.isSimultaneousMove)
    {
      if (roleIndex == 0)
      {
        return inboundEdge.child.node.averageScores[roleIndex] / 100;
      }
      return Math
          .min(inboundEdge.child.node.averageScores[roleIndex] / 100,
               getAverageCousinMoveValue(inboundEdge, roleIndex));
    }

    double result = inboundEdge.child.node.averageScores[roleIndex] / 100;// + heuristicValue()/Math.log(numVisits+2);// + averageSquaredScore/20000;
    return result;
    //return inboundEdge.child.node.averageScores[roleIndex]/100;
    //return inboundEdge.child.node.averageScores[roleIndex]/100 + getAverageCousinMoveValue(inboundEdge, roleIndex)/Math.log(inboundEdge.child.node.numVisits+2);// + heuristicValue()/Math.log(numVisits+2);// + averageSquaredScore/20000;
  }

  private final int orderStatistic = 3;
  private double[]  orderBuffer    = new double[orderStatistic];

  //	Weighted average of available responses (modified (RMS) third-order statistic currently)
  private double getDescendantMoveWeight(MoveWeightsCollection weights,
                                         int roleIndex)
  {
    double result = 0;
    MoveWeightsCollection ourWeights = tree.nodeMoveWeightsCache.get(this);

    if (children != null)
    {
      if (children.length > 1)
      {
        if ((decidingRoleIndex + 1) % tree.numRoles == roleIndex)
        {
          int maxOrderIndex = Math.min(children.length, orderStatistic);

          for (int order = 0; order < maxOrderIndex; order++)
          {
            orderBuffer[order] = 0;
          }

          for (TreeEdge edge : children)
          {
            double ancestorVal = weights
                .getMoveWeight(edge.jointPartialMove[roleIndex].move,
                               roleIndex);
            double ourVal = (ourWeights == null ? 0 : ourWeights
                                                .getMoveWeight(edge.jointPartialMove[roleIndex].move,
                                                               roleIndex));
            double maxVal = Math.max(ancestorVal, ourVal);

            for (int order = 0; order < maxOrderIndex; order++)
            {
              if (maxVal > orderBuffer[order])
              {
                for (int i = order + 1; i < maxOrderIndex; i++)
                {
                  orderBuffer[i] = orderBuffer[i - 1];
                }

                orderBuffer[order] = maxVal;
                break;
              }
            }
          }

          for (int order = 0; order < maxOrderIndex; order++)
          {
            result += orderBuffer[order] * orderBuffer[order];
          }

          result = Math.sqrt(result / maxOrderIndex);
        }
      }
      else
      {
        return children[0].child.node.getDescendantMoveWeight(weights,
                                                              roleIndex);
      }
    }

    return result;
  }

  private TreePathElement select(TreePath path, TreeEdge from, MoveWeightsCollection weights)
      throws MoveDefinitionException, TransitionDefinitionException,
      GoalDefinitionException
      {
    TreeEdge selected = null;
    int selectedIndex = -1;
    int bestSelectedIndex = -1;
    double bestCompleteValue = Double.MIN_VALUE;
    TreeNode bestCompleteNode = null;
    double bestValue = Double.MIN_VALUE;

    //	Find the role this node is choosing for
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

    tree.cousinMovesCachedFor = null;
    //System.out.println("Select in " + state);
    if (trimmedChildren == 0)
    {
      if (children != null)
      {
        //  If there is only one choice we have to select it
        if (children.length == 1)
        {
          TreeEdge edge = children[0];
          TreeNodeRef cr = edge.child;

          if (cr.node.seq == cr.seq)
          {
            selectedIndex = 0;
          }
          else
          {
            trimmedChildren = 1;
            tree.numIncompleteNodes++;
          }
        }
        else
        {
          //  We cache the best and second best selections so that on future selects through
          //  the node we only have to check that the best has not fallen in value below the
          //  second best, and do a full rescan only if it has (a few operations also clear the cached
          //  value, such as completion processing)
          mostLikelyRunnerUpValue = Double.MIN_VALUE;

          //  If action histories are in use we need to accrue the weights from this node
          if (tree.moveActionHistoryBias != 0)
          {
            MoveWeightsCollection ourWeights = tree.nodeMoveWeightsCache.get(this);

            weights.decay();
            if (ourWeights != null)
            {
              weights.accrue(ourWeights);
            }
          }

          if (mostLikelyWinner != -1)
          {
            TreeNodeRef cr = children[mostLikelyWinner].child;
            TreeNode c = cr.node;
            if (cr.seq == c.seq && (!c.complete) && !c.allChildrenComplete)
            {
              double uctValue;

              if (c.numVisits == 0 && !c.complete)
              {
                // small random number to break ties randomly in unexpanded nodes
                uctValue = 1000 + tree.r.nextDouble() * EPSILON;
              }
              else
              {
                //  Various experiments have been done to try to find the best selection
                //  weighting, and it seems that using the number of times we've visited the
                //  child FROM THIS PARENT coupled with the num visits here in standard UCT
                //  manner works best.  In particular using the visit count on the child node
                //  (rather than through the child edge to it, which can be radically different
                //  in highly transpositional games) does not seem to work as well (even with a
                //  'corrected' parent visit count obtained by summing the number of visits to all
                //  the child's parents)
                uctValue = explorationUCT(numVisits,
                                          children[mostLikelyWinner].numChildVisits,
                                          roleIndex) +
                                          exploitationUCT(children[mostLikelyWinner],
                                                          roleIndex);
              }

              if (uctValue >= mostLikelyRunnerUpValue)
              {
                selectedIndex = mostLikelyWinner;
              }
            }
          }

          if (selectedIndex == -1)
          {
            //  Previous second best now preferred over previous best so we need
            //  to recalculate
            mostLikelyRunnerUpValue = Double.MIN_VALUE;

            for (int i = 0; i < children.length; i++)
            {
              TreeNodeRef cr = children[i].child;
              if (cr != null)
              {
                TreeNode c = cr.node;
                if (c.seq != cr.seq)
                {
                  if (cr.seq != -1)
                  {
                    if (trimmedChildren++ == 0)
                    {
                      tree.numIncompleteNodes++;
                    }
                    cr.seq = -1;
                  }

                  selectedIndex = -1;
                  break;
                }
                //  Only select one move that is state-equivalent
                else if (children[i].selectAs == children[i])
                {
                  double uctValue;
                  if (children[i].numChildVisits == 0 && !c.complete)
                  {
                    // small random number to break ties randomly in unexpanded nodes
                    uctValue = 1000 + tree.r.nextDouble() * EPSILON;
                  }
                  else
                  {
                    //  Various experiments have been done to try to find the best selection
                    //  weighting, and it seems that using the number of times we've visited the
                    //  child FROM THIS PARENT coupled with the num visits here in standard UCT
                    //  manner works best.  In particular using the visit count on the child node
                    //  (rather than through the child edge to it, which can be radically different
                    //  in highly transpositional games) does not seem to work as well (even with a
                    //  'corrected' parent visit count obtained by summing the number of visits to all
                    //  the child's parents)
                    //  Empirically the half value for the exploration term applied to complete
                    //  children seems to give decent results.  Both applying it in full and not
                    //  applying it (both of which can be rationalized!) seem to fare worse in at
                    //  least some games
                    uctValue = (c.complete ? explorationUCT(numVisits,
                                                            children[i].numChildVisits,
                                                            roleIndex)/2
                                                            : explorationUCT(numVisits,
                                                                             children[i].numChildVisits,
                                                                             roleIndex)) +
                                                                             exploitationUCT(children[i], roleIndex);
                  }

                  //  If we're suing move action histories add the move weight into the selection value
                  if (tree.moveActionHistoryBias != 0)
                  {
                    double moveWeight = 0;
                    double opponentEnabledMoveWeight = c
                        .getDescendantMoveWeight(weights, roleIndex);
                    if (!c.complete && weights != null)
                    {
                      moveWeight = weights
                          .getMoveWeight(children[i].jointPartialMove[roleIndex].move,
                                         roleIndex);
                    }
                    uctValue += (moveWeight - opponentEnabledMoveWeight) *
                        Math.sqrt(Math.log(numVisits) /
                                  (c.numVisits + 1)) *
                                  tree.moveActionHistoryBias;
                  }

                  //  If the node we most want to select through is complete (or all its
                  //  children are, in either case of which there is nothing further to
                  //  learn) we select the best non-compleet choice but record the fact
                  //  so that on propagation of the result we can propagate upwards from this
                  //  node the score of the complete node that in some sense 'should' have
                  //  been selected
                  if (!c.complete && !c.allChildrenComplete)
                  {
                    if (uctValue > bestValue)
                    {
                      selectedIndex = i;
                      if (bestValue != Double.MIN_VALUE)
                      {
                        mostLikelyRunnerUpValue = bestValue;
                      }
                      bestValue = uctValue;
                    }
                  }
                  else
                  {
                    if (uctValue > bestCompleteValue)
                    {
                      bestCompleteValue = uctValue;
                      bestCompleteNode = c;
                      bestSelectedIndex = i;
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    if (selectedIndex == -1)
    {
      if (children == null)
      {
        System.out.println("select on an unexpanded node!");
      }
      //	pick at random.  If we pick one that has been trimmed re-expand it
      //	FUTURE - can establish a bound on the trimmed UCT value to avoid
      //	randomization for a while at least
      int childIndex = tree.r.nextInt(children.length);
      selected = children[childIndex];
      TreeNodeRef cr = selected.child;

      tree.numSelectionsThroughIncompleteNodes++;

      if (cr.seq != cr.node.seq)
      {
        tree.numReExpansions++;

        expand(from);
        selected = children[childIndex];

        if (selected.child.node.freed)
        {
          System.out.println("Selected freed node!");
        }
        if (selected.child.node.complete && !tree.gameCharacteristics.isMultiPlayer && !tree.gameCharacteristics.isPuzzle)
        {
          if (!tree.completeSelectionFromIncompleteParentWarned)
          {
            tree.completeSelectionFromIncompleteParentWarned = true;
            System.out
            .println("Selected complete node from incomplete parent");
          }
        }
      }
    }
    else
    {
      mostLikelyWinner = selectedIndex;
      selected = children[selectedIndex];

      if (selected.child.node.freed)
      {
        System.out.println("Selected freed node!");
      }
    }

    TreePathElement result = path.new TreePathElement(selected);

    //  If the node that should have been selected through was complete
    //  note that in the path, so that on application of the update
    //  the propagation upward from this node can be corrected
    //  HACK - we disable this, at least for now, in puzzles because of MaxKnights
    //  which happens to do well from the distorted stats you get without it.  This
    //  is due to the particular circumstance in MaxKnights that scores can only
    //  go up!
    if (bestCompleteNode != null && bestCompleteValue > bestValue && !tree.gameCharacteristics.isPuzzle)
    {
      result.setScoreOverrides(bestCompleteNode.averageScores);
      bestCompleteNode.numVisits++;
      children[bestSelectedIndex].numChildVisits++;
      mostLikelyWinner = -1;
    }

    //  Decay the weights being aggregated (so that they decay progressively as
    //  we select down the tree)
    if (tree.moveActionHistoryBias != 0 && weights != null)
    {
      weights
      .decayForSelectionThrough(selected.jointPartialMove[roleIndex].move,
                                roleIndex);
    }

    return result;
  }

  public boolean isUnexpanded()
  {
    return children == null || complete;
  }

  private double scoreForMostLikelyResponseRecursive(TreeNode from,
                                                     int forRoleIndex)
  {
    //	Stop recursion at the next choice
    if (children == null || complete)
    {
      return averageScores[forRoleIndex];
    }
    else if ((decidingRoleIndex + 1) % tree.numRoles == forRoleIndex &&
        from != null && children.length > 1)
    {
      return from.averageScores[forRoleIndex]; //	TEMP TEMP TEMP
    }

    double result = 0;
    double childResult = -Double.MAX_VALUE;

    for (TreeEdge edge : children)
    {
      if (edge.child.seq == edge.child.node.seq)
      {
        double childVal = edge.child.node.averageScores[edge.child.node.decidingRoleIndex];

        if (childVal > childResult)//&& edge.child.node.numVisits > 500 )
        {
          childResult = childVal;
          result = edge.child.node
              .scoreForMostLikelyResponseRecursive(this, forRoleIndex);
        }
      }
    }

    return (childResult == -Double.MAX_VALUE ? averageScores[forRoleIndex]
        : Math
        .min(result,
             averageScores[forRoleIndex]));
  }

  private double scoreForMostLikelyResponse()
  {
    return scoreForMostLikelyResponseRecursive(null, decidingRoleIndex);
  }

  private String stringizeScoreVector(double[] averageScores,
                                      boolean complete)
  {
    StringBuilder sb = new StringBuilder();

    sb.append("[");
    for (int i = 0; i < tree.numRoles; i++)
    {
      if (i > 0)
      {
        sb.append(", ");
      }
      sb.append(averageScores[i]);
    }
    sb.append("]");
    if (complete)
    {
      sb.append(" (complete)");
    }

    return sb.toString();
  }

  private String stringizeScoreVector()
  {
    return stringizeScoreVector(averageScores, complete);
  }

  private void traceFirstChoiceNode()
  {
    if (children == null)
    {
      System.out.println("    No choice response scores " +
          stringizeScoreVector());
    }
    else if (children.length > 1)
    {
      for (TreeEdge edge2 : children)
      {
        if (edge2.child.seq == edge2.child.node.seq)
        {
          System.out
          .println("    Response " +
              edge2.jointPartialMove[edge2.child.node.decidingRoleIndex].move +
              " scores " + edge2.child.node.stringizeScoreVector() +
              ", visits " + edge2.child.node.numVisits +
              ", seq : " + edge2.child.seq +
              (edge2.child.node.complete ? " (complete)" : ""));
        }
      }
    }
    else
    {
      children[0].child.node.traceFirstChoiceNode();
    }
  }

  private void indentedPrint(PrintWriter writer, int depth, String line)
  {
    StringBuilder indent = new StringBuilder();

    for (int i = 0; i < depth; i++)
    {
      indent.append(" ");
    }
    writer.println(indent + line);
  }

  private void dumpTree(PrintWriter writer, int depth, TreeEdge arrivalPath)
  {
    if (arrivalPath == null)
    {
      indentedPrint(writer, depth * 2, "Root scores " +
          stringizeScoreVector());
    }
    else
    {
      indentedPrint(writer,
                    depth * 2,
                    "@" +
                        depth +
                        ": Move " +
                        arrivalPath.jointPartialMove[decidingRoleIndex].move +
                        " scores " + stringizeScoreVector() + "(seq " + seq +
                        ") - visits: " + numVisits + " (" +
                        arrivalPath.numChildVisits + ")");
    }

    if (sweepSeq == tree.sweepInstance)
    {
      indentedPrint(writer, (depth + 1) * 2, "...transition...");
    }
    else
    {
      sweepSeq = tree.sweepInstance;

      if (children != null)
      {
        for (TreeEdge edge : children)
        {
          if (edge.child.node.seq == edge.child.seq)
          {
            edge.child.node.dumpTree(writer, depth + 1, edge);
          }
        }
      }
    }
  }

  private void dumpTree(String filename)
  {
    tree.sweepInstance++;

    try
    {
      File f = new File(filename);
      PrintWriter writer = new PrintWriter(f);
      dumpTree(writer, 0, null);
      writer.close();
    }
    catch (Exception e)
    {
      GamerLogger.logStackTrace("StateMachine", e);
    }
  }

  private void postProcessResponseCompletion()
  {
    if (children != null)
    {
      if (children.length > 1)
      {
        if (decidingRoleIndex != (tree.numRoles - 1) % tree.numRoles)
        {
          for (TreeEdge edge2 : children)
          {
            if (edge2.child.seq == edge2.child.node.seq)
            {
              if (edge2.child.node.averageScores[0] <= tree.rolloutPool.lowestRolloutScoreSeen &&
                  edge2.child.node.complete)
              {
                System.out
                .println("Post-processing completion of response node");
                markComplete(edge2.child.node.averageScores);
                processCompletion();
              }
            }
          }
        }
      }
      else
      {
        children[0].child.node.postProcessResponseCompletion();
      }
    }
  }

  private class MoveFrequencyInfo
  {
    public int      numSamples;
    public int      selectionFrequency;
    public double[] weightedAverageScores;
    public double   averageWeight;

    public MoveFrequencyInfo()
    {
      weightedAverageScores = new double[TreeNode.this.tree.numRoles];
    }
  }

  private void addResponseStats(Map<Move, MoveFrequencyInfo> responseInfo,
                                MoveWeightsCollection weights)
  {
    weights.decay();

    if (children != null)
    {
      if (children.length > 1)
      {
        MoveWeightsCollection ourWeights = tree.nodeMoveWeightsCache.get(this);
        if (ourWeights != null)
        {
          weights.accrue(ourWeights);
        }

        for (TreeEdge edge2 : children)
        {
          if (edge2.child.seq == edge2.child.node.seq)
          {
            MoveFrequencyInfo moveInfo = responseInfo
                .get(edge2.jointPartialMove[edge2.child.node.decidingRoleIndex].move);

            if (moveInfo == null)
            {
              moveInfo = new MoveFrequencyInfo();
              responseInfo
              .put(edge2.jointPartialMove[edge2.child.node.decidingRoleIndex].move,
                   moveInfo);
            }

            for (int i = 0; i < tree.numRoles; i++)
            {
              moveInfo.weightedAverageScores[i] = (moveInfo.weightedAverageScores[i] *
                  moveInfo.selectionFrequency + edge2.child.node.averageScores[i] *
                  edge2.child.node.numVisits) /
                  (moveInfo.selectionFrequency + edge2.child.node.numVisits);
            }
            moveInfo.selectionFrequency += edge2.child.node.numVisits;

            moveInfo.averageWeight = (moveInfo.averageWeight *
                moveInfo.numSamples + weights
                .getMoveWeight(edge2.jointPartialMove[edge2.child.node.decidingRoleIndex].move,
                               edge2.child.node.decidingRoleIndex)) /
                               (moveInfo.numSamples + 1);
            moveInfo.numSamples++;
          }
        }
      }
      else
      {
        children[0].child.node.addResponseStats(responseInfo, weights);
      }
    }
  }

  private void dumpStats()
  {
    Map<Move, MoveFrequencyInfo> moveChoices = new HashMap<Move, MoveFrequencyInfo>();
    Map<Move, MoveFrequencyInfo> responseChoices = new HashMap<Move, MoveFrequencyInfo>();
    MoveWeightsCollection ourWeights = tree.nodeMoveWeightsCache.get(this);

    if (children != null)
    {
      for (TreeEdge edge : children)
      {
        MoveFrequencyInfo info = new MoveFrequencyInfo();

        info.selectionFrequency = edge.numChildVisits;
        info.weightedAverageScores = edge.child.node.averageScores;
        info.numSamples = 1;
        info.averageWeight = (ourWeights == null ? 0 : ourWeights
                                                 .getMoveWeight(edge.jointPartialMove[0].move, 0));
        moveChoices.put(edge.jointPartialMove[0].move, info);

        MoveWeightsCollection weights = new MoveWeightsCollection(tree.numRoles);
        if (ourWeights != null)
        {
          weights.accrue(ourWeights);
        }
        edge.child.node.addResponseStats(responseChoices, weights);
      }
    }

    for (Entry<Move, MoveFrequencyInfo> e : moveChoices.entrySet())
    {
      System.out
      .println("Move " +
          e.getKey() +
          " weight " +
          e.getValue().averageWeight +
          ", frequency " +
              e.getValue().selectionFrequency +
              ", score: " +
                  stringizeScoreVector(e.getValue().weightedAverageScores,
                                       false));
    }

    for (Entry<Move, MoveFrequencyInfo> e : responseChoices.entrySet())
    {
      System.out
      .println("Response " +
          e.getKey() +
          " weight " +
          e.getValue().averageWeight +
          ", frequency " +
          e.getValue().selectionFrequency +
          ", score: " +
          stringizeScoreVector(e.getValue().weightedAverageScores,
                               false));
    }
  }

  public Move getBestMove(boolean traceResponses, StringBuffer pathTrace)
  {
    double bestScore = -Double.MAX_VALUE;
    double bestRawScore = -Double.MAX_VALUE;
    int mostSelected = -Integer.MAX_VALUE;
    Move rawResult = null;
    Move result = null;
    boolean anyComplete = false;
    TreeNode bestNode = null;

    // This routine is called recursively for path tracing purposes.  When
    // calling this routing for path tracing purposes, don't make any other
    // debugging output (because it would be confusing).
    boolean lRecursiveCall = (pathTrace != null);

    for (TreeEdge edge : children)
    {
      if (edge.child.node.complete)
      {
        anyComplete = true;
      }
      else if (edge.child.node.children != null &&
          tree.rolloutPool.lowestRolloutScoreSeen < 100 && !tree.gameCharacteristics.isMultiPlayer &&
          !tree.gameCharacteristics.isSimultaneousMove)
      {
        //	Post-process completions of children with respect the the observed rollout score range
        edge.child.node.postProcessResponseCompletion();
      }
    }

    for (TreeEdge edge : children)
    {
      TreeNode child = edge.child.node;

      double selectionScore;
      double moveScore = (tree.gameCharacteristics.isSimultaneousMove ||
                          tree.gameCharacteristics.isMultiPlayer ||
                          anyComplete ||
                          tree.disableOnelevelMinimax) ? child.averageScores[0] :
                                                         child.scoreForMostLikelyResponse();
      //	If we have complete nodes with equal scores choose the one with the highest variance
      if (child.complete)
      {
        double varianceMeasure = child.averageSquaredScores[0] / 100;

        if (moveScore < 0.1)
        {
          moveScore = varianceMeasure - 100;
        }

        selectionScore = moveScore;
      }
      else
      {
        selectionScore = moveScore *
            (1 - 20 * Math.log(numVisits) /
                (20 * Math.log(numVisits) + child.numVisits));
      }
      if (!lRecursiveCall)
      {
        System.out.println("Move " + edge.jointPartialMove[0].move +
                           " scores " + moveScore + " (selectionScore score " +
                           selectionScore + ", selection count " +
                           child.numVisits + ", seq " + child.seq +
                           (child.complete ? ", complete" : "") + ")");
      }

      if (child.children != null && !child.complete && traceResponses)
      {
        child.traceFirstChoiceNode();
      }
      //	Don't accept a complete score which no rollout has seen worse than, if there is
      //	any alternative
      if (bestNode != null && !bestNode.complete && child.complete &&
          moveScore <= tree.rolloutPool.lowestRolloutScoreSeen &&
          tree.rolloutPool.lowestRolloutScoreSeen < 100)
      {
        continue;
      }
      if (selectionScore > bestScore ||
          (moveScore == bestScore && child.complete && (child.numVisits > mostSelected || !bestNode.complete)) ||
          (bestNode != null && bestNode.complete && !child.complete &&
          bestNode.averageScores[0] <= tree.rolloutPool.lowestRolloutScoreSeen && tree.rolloutPool.lowestRolloutScoreSeen < 100))
      {
        bestNode = child;
        bestScore = selectionScore;
        mostSelected = child.numVisits;
        result = edge.jointPartialMove[0].move;
      }
      if (child.averageScores[0] > bestRawScore ||
          (child.averageScores[0] == bestRawScore && child.complete && child.averageScores[0] > 0))
      {
        bestRawScore = child.averageScores[0];
        rawResult = edge.jointPartialMove[0].move;
      }
    }

    dumpStats();

    //dumpTree("C:\\temp\\mctsTree.txt");

    if (!lRecursiveCall)
    {
      if (result == null)
      {
        System.out.println("No move found!");
      }
      if (rawResult != result)
      {
        System.out
        .println("1 level minimax result differed from best raw move: " +
            rawResult);
      }
    }

    // Trace the most likely path through the tree
    if (!lRecursiveCall)
    {
      pathTrace = new StringBuffer("Most likely path: ");
    }
    assert(pathTrace != null);
    pathTrace.append(result); // !! ARR No.  Needs to be the move for the deciding role
    pathTrace.append(", ");

    if ((bestNode != null) && (bestNode.children != null))
    {
      bestNode.getBestMove(false, pathTrace);
    }
    else
    {
      System.out.println(pathTrace.toString());
    }

    return result;
  }

  public RolloutRequest rollOut(TreePath path)
      throws InterruptedException
  {
    RolloutRequest request = tree.rolloutPool.createRolloutRequest();

    if (complete)
    {
      //System.out.println("Terminal state " + state + " score " + averageScore);
      tree.numTerminalRollouts++;

      for (int i = 0; i < tree.numRoles; i++)
      {
        request.averageScores[i] = averageScores[i];
        request.averageSquaredScores[i] = averageSquaredScores[i];
      }

      return request;
    }
    if (decidingRoleIndex != tree.numRoles - 1)
    {
      System.out.println("Unexpected rollout state");
    }

    request.state = state;
    request.node = getRef();
    request.sampleSize = tree.rolloutSampleSize;
    request.path = path;
    //request.moveWeights = masterMoveWeights.copy();

    tree.rolloutPool.enqueueRequest(request);

    return null;
  }

  public void updateVisitCounts(int sampleSize, TreePath path)
  {
    TreePathElement element = path.getCurrentElement();
    TreeEdge childEdge = (element == null ? null : element.getEdge());

    numVisits++;// += sampleSize;

    //for(TreeNode parent : parents)
    //{
    //	if ( !parent.complete || isSimultaneousMove || isMultiPlayer )
    //	{
    //		parent.updateVisitCounts(sampleSize, path);
    //	}
    //}

    if (childEdge != null)
    {
      childEdge.numChildVisits++;

      if (childEdge.numChildVisits > childEdge.child.node.numVisits)
      {
        System.out.println("Edge count greater than target visit count");
      }
    }

    if (path.hasMore())
    {
      TreeNode node = path.getNextNode();
      if (node != null)
      {
        node.updateVisitCounts(sampleSize, path);
      }
    }
  }

  private int dumpCount          = 0;
  double      lastDebugNodeScore = 100;

  public void updateStats(double[] values,
                          double[] squaredValues,
                          int sampleSize,
                          TreePath path,
                          boolean isCompletePseudoRollout)
  {
    TreePathElement element = path.getCurrentElement();
    TreeEdge childEdge = (element == null ? null : element.getEdge());

    double[] oldAverageScores = new double[tree.numRoles];
    double[] oldAverageSquaredScores = new double[tree.numRoles];
    boolean visitCountsUpdated = false;

    double[] overrides = (element == null ? null : element
                                          .getScoreOverrides());
    if (overrides != null)
    {
      values = overrides;
    }
    else if (childEdge != null && children.length > 1 &&
        tree.moveActionHistoryBias > 0)
    {
      //	Sigmoid response to score in move weight, biased around a score of 75
      double newWeight = 1 / (1 + Math
          .exp((75 - childEdge.child.node.averageScores[childEdge.child.node.decidingRoleIndex]) / 5));

      if (!childEdge.child.node.complete)
      {
        newWeight *= (1 - Math.exp(-childEdge.child.node.numVisits / 10));
      }

      path.propagatedMoveWeights.decay();
      path.propagatedMoveWeights
      .addMove(childEdge.jointPartialMove[childEdge.child.node.decidingRoleIndex].move,
               childEdge.child.node.decidingRoleIndex,
               newWeight);

      MoveWeightsCollection ourWeights = tree.nodeMoveWeightsCache.get(this);

      if (ourWeights == null)
      {
        ourWeights = new MoveWeightsCollection(tree.numRoles);
        tree.nodeMoveWeightsCache.put(this, ourWeights);
      }

      ourWeights.accrue(path.propagatedMoveWeights);
    }

    for (int roleIndex = 0; roleIndex < tree.numRoles; roleIndex++)
    {
      oldAverageScores[roleIndex] = averageScores[roleIndex];
      oldAverageSquaredScores[roleIndex] = averageSquaredScores[roleIndex];

      if ((!complete || tree.gameCharacteristics.isSimultaneousMove || tree.gameCharacteristics.isMultiPlayer) &&
          childEdge != null)
      {
        int numChildVisits = childEdge.numChildVisits;

        if (numChildVisits > childEdge.child.node.numVisits)
        {
          System.out
          .println("Unexpected edge strength greater than total child strength");
        }
        //	Propagate a value that is a blend of this rollout value and the current score for the child node
        //	being propagated from, according to how much of that child's value was accrued through this path
        if (values != overrides)
        {
          values[roleIndex] = (values[roleIndex] * numChildVisits + childEdge.child.node.averageScores[roleIndex] *
              (childEdge.child.node.numVisits - numChildVisits)) /
              childEdge.child.node.numVisits;
        }
      }

      if (!complete)
      {
        averageScores[roleIndex] = (averageScores[roleIndex] * numUpdates + values[roleIndex]) /
            (numUpdates + 1);
        averageSquaredScores[roleIndex] = (averageSquaredScores[roleIndex] *
            numUpdates + squaredValues[roleIndex]) /
            (numUpdates + 1);
      }

      if (complete && !tree.gameCharacteristics.isSimultaneousMove && !tree.gameCharacteristics.isMultiPlayer &&
          averageScores[roleIndex] != oldAverageScores[roleIndex])
      {
        System.out.println("Unexpected update to complete node score");
      }

      leastLikelyWinner = -1;
      mostLikelyWinner = -1;
    }

    if (isCompletePseudoRollout)
    {
      numVisits++;

      if ((!complete || tree.gameCharacteristics.isSimultaneousMove || tree.gameCharacteristics.isMultiPlayer) &&
          childEdge != null)
      {
        childEdge.numChildVisits++;
      }
    }

    //validateScoreVector(averageScores);
    numUpdates++;

    if (path.hasMore())
    {
      TreeNode node = path.getNextNode();
      if (node != null)
      {
        node.updateStats(values,
                         squaredValues,
                         sampleSize,
                         path,
                         isCompletePseudoRollout);
      }
    }
  }
}