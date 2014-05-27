package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeRef;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;

public class StateSimilarityMap
{
  private class StateSimilarityBucket
  {
    private final int capacity = 4;

    public final TreeNodeRef[] refs = new TreeNodeRef[capacity];
    public int  size = 0;

    public void addNode(TreeNodeRef nodeRef)
    {
      for(int i = 0; i < size; i++)
      {
        if ( nodeRef.seq == refs[i].seq )
        {
          //  Already present
          return;
        }
      }
      if ( size < capacity )
      {
        refs[size++] = nodeRef;
      }
      else
      {
        int evictee = -1;
        double highestEvictionMeasure = -Double.MAX_VALUE;

        for(int i = 0; i < capacity; i++)
        {
          TreeNodeRef ref = refs[i];
          if ( ref.seq != ref.node.seq )
          {
            evictee = i;
            break;
          }

          double evictionMeasure = -Math.log(ref.node.numVisits+1);//ref.node.getAge()/(ref.node.numVisits+1);

          if ( evictionMeasure > highestEvictionMeasure )
          {
            evictionMeasure = highestEvictionMeasure;
            evictee = i;
          }
        }

        refs[evictee] = nodeRef;
      }
    }
  }

  final private StateSimilarityBucket[] buckets;
  final private StateSimilarityHashGenerator hashGenerator;
  final private int maxMovesConsidered = 64;
  final private ForwardDeadReckonLegalMoveInfo[] moveBuffer = new ForwardDeadReckonLegalMoveInfo[maxMovesConsidered];
  final private double[] moveValueBuffer = new double[maxMovesConsidered];
  final private double[] moveWeightBuffer = new double[maxMovesConsidered];
  final private double[] topValues = new double[maxMovesConsidered];
  final private double[] topWeights = new double[maxMovesConsidered];
  private int numMovesBuffered;

  public StateSimilarityMap(ForwardDeadReckonPropNet propNet)
  {
    hashGenerator = new StateSimilarityHashGenerator(propNet);
    buckets = new StateSimilarityBucket[1<<StateSimilarityHashGenerator.hashSize];
  }

  public void add(TreeNodeRef nodeRef)
  {
    int hash = hashGenerator.getHash(nodeRef.node.state);

    if ( buckets[hash] == null )
    {
      buckets[hash] = new StateSimilarityBucket();
    }
    buckets[hash].addNode(nodeRef);
  }

  public int getScoreEstimate(ForwardDeadReckonInternalMachineState state, double[] result)
  {
    for(int i = 0; i < result.length; i++)
    {
      result[i] = 0;
    }

    int hash = hashGenerator.getHash(state);

    StateSimilarityBucket bucket = buckets[hash];
    if ( bucket != null )
    {
      double totalWeight = 0;

      for(int i = 0; i < bucket.size; i++)
      {
        TreeNodeRef nodeRef = bucket.refs[i];

        if ( nodeRef.seq == nodeRef.node.seq && nodeRef.node.numVisits > 0 && state != nodeRef.node.state )
        {
          double distanceWeight = (1 - state.distance(nodeRef.node.state));
          double weight = distanceWeight*distanceWeight*Math.log(nodeRef.node.numVisits+1);

          for(int j = 0; j < result.length; j++)
          {
            result[j] += nodeRef.node.averageScores[j]*weight;
            assert(!Double.isNaN(result[j]));
          }

          totalWeight += weight;
        }
      }

      if ( totalWeight > 0 )
      {
        for(int i = 0; i < result.length; i++)
        {
          result[i] /= totalWeight;
          assert(!Double.isNaN(result[i]));
        }
      }
      return (int)(totalWeight);
    }

    return 0;
  }

  private TreeNode getJointMoveParent(TreeNode moveRoot, ForwardDeadReckonLegalMoveInfo[] partialJointMove)
  {
    int index = 0;
    TreeNode result = null;

    if ( partialJointMove[partialJointMove.length-1] != null )
    {
      return moveRoot;
    }

    while(index < partialJointMove.length && partialJointMove[index] != null)
    {
      if ( moveRoot.children == null )
      {
        return null;
      }

      boolean childFound = false;
      for(TreeEdge childEdge : moveRoot.children)
      {
        if ( childEdge.jointPartialMove[index] == partialJointMove[index] )
        {
          childFound = true;

          if ( childEdge.child.seq >= 0 &&
               childEdge.child.seq == childEdge.child.node.seq &&
               childEdge.child.node.children != null )
          {
            result = childEdge.child.node;
            moveRoot = result;
            index++;
            break;
          }

          return null;
        }
      }

      if ( !childFound )
      {
        return null;
      }
    }

    return result;
  }

  private int getMoveSlot(ForwardDeadReckonLegalMoveInfo move)
  {
    for(int i = 0; i < numMovesBuffered; i++)
    {
      if ( moveBuffer[i] == move )
      {
        return i;
      }
    }

    if ( numMovesBuffered < maxMovesConsidered )
    {
      numMovesBuffered++;
    }

    moveBuffer[numMovesBuffered-1] = move;
    moveWeightBuffer[numMovesBuffered-1] = 0;
    moveValueBuffer[numMovesBuffered-1] = 0;

    return numMovesBuffered-1;
  }

  public int getTopMoves(ForwardDeadReckonInternalMachineState state, ForwardDeadReckonLegalMoveInfo[] partialJointMove, ForwardDeadReckonLegalMoveInfo[] result)
  {
    int hash = hashGenerator.getHash(state);

    numMovesBuffered = 0;

    int hammingCloseHash = hash;

    for(int nearbyHashIndex = 0; nearbyHashIndex <= StateSimilarityHashGenerator.hashSize; nearbyHashIndex++)
    {
      StateSimilarityBucket bucket = buckets[hammingCloseHash];
      if ( bucket != null )
      {
        for(int i = 0; i < bucket.size; i++)
        {
          TreeNodeRef nodeRef = bucket.refs[i];

          if ( nodeRef.seq == nodeRef.node.seq && nodeRef.node.numVisits > 0 && state != nodeRef.node.state )
          {
            double distanceWeight = (1 - state.distance(nodeRef.node.state));
            double weight = distanceWeight*distanceWeight*Math.log10(nodeRef.node.numVisits+1);

            TreeNode node = getJointMoveParent(nodeRef.node, partialJointMove);
            if ( node != null && node.children != null )
            {
              for(TreeEdge childEdge : node.children)
              {
                if ( childEdge.child.seq >= 0 &&
                     childEdge.child.seq == childEdge.child.node.seq &&
                     childEdge.child.node.numVisits > 0 )
                {
                  ForwardDeadReckonLegalMoveInfo move = childEdge.jointPartialMove[childEdge.child.node.decidingRoleIndex];
                  int moveSlotIndex = getMoveSlot(move);

                  double moveVal = weight*(childEdge.child.node.averageScores[childEdge.child.node.decidingRoleIndex]);

                  moveValueBuffer[moveSlotIndex] = (moveValueBuffer[moveSlotIndex]*moveWeightBuffer[moveSlotIndex] + moveVal)/(moveWeightBuffer[moveSlotIndex] + weight);
                  moveWeightBuffer[moveSlotIndex] += weight;
                }
              }
            }
          }
        }

        //  We look at all hashes within a Hamming distance of 1 from the original
        hammingCloseHash = hash ^ (1<<nearbyHashIndex);
      }

      //System.out.println("Found " + numMovesBuffered + " moves to buffer");
      int numTopMoves = 0;
      for(int i = 0; i < numMovesBuffered; i++)
      {
        int index = numTopMoves - 1;

        while( index >= 0 && moveValueBuffer[i] > topValues[index] )
        {
          index--;
        }

        if ( ++index < result.length )
        {
          for(int j = numTopMoves-1; j > index; j--)
          {
            topValues[j] = topValues[j-1];
            topWeights[j] = topWeights[j-1];

            result[j] = result[j-1];
          }

          topValues[index] = moveValueBuffer[i];
          topWeights[index] = moveWeightBuffer[i];
          result[index] = moveBuffer[i];

          if ( index == numTopMoves )
          {
            numTopMoves = index+1;
          }
        }
      }

      int i;
      double totalWeight = 0;
      double bestScore = topValues[0];
      final double ratioToBestCutoff = 0.8;

      for(i = 0; i < numTopMoves; i++)
      {
        if ( topValues[i] < ratioToBestCutoff*bestScore )
        {
          numTopMoves = i;
          break;
        }
        totalWeight += topWeights[i];
      }
      while(i < result.length)
      {
        result[i++] = null;
      }

      if ( numTopMoves > 0 )
      {
        totalWeight /= numTopMoves;
      }

      return (int)(totalWeight);
    }

    return 0;
  }
}
