package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashMap;
import java.util.Map.Entry;

import org.ggp.base.util.statemachine.Move;

class MoveWeightsCollection
{
  private class MoveWeight
  {
    public double value;
    public int    numSamples;
  }

  private class MoveWeights extends HashMap<Move, MoveWeight>
  {
    /*
     *
     */
    private static final long serialVersionUID = 1L;
  }

  /**
   *
   */
  private int numRoles;
  public final static double decayRate              = 0.8;
  public final static double selectThroughDecayRate = 0.0;

  MoveWeights[]              roleMoveWeights        = null;

  public MoveWeightsCollection(int numRoles)
  {
    this.numRoles = numRoles;
    roleMoveWeights = new MoveWeights[numRoles];

    for (int i = 0; i < numRoles; i++)
    {
      roleMoveWeights[i] = new MoveWeights();
    }
  }

  public void addMove(Move move, int roleIndex, double weight)
  {
    MoveWeight existingValue = roleMoveWeights[roleIndex].get(move);
    if (existingValue == null)
    {
      existingValue = new MoveWeight();
      roleMoveWeights[roleIndex].put(move, existingValue);
    }

    existingValue.value = (existingValue.value * existingValue.numSamples + weight) /
                          ++existingValue.numSamples;
  }

  public double getMoveWeight(Move move, int roleIndex)
  {
    MoveWeight existingValue = roleMoveWeights[roleIndex].get(move);

    return (existingValue == null ? 0 : existingValue.value);
  }

  public void accrue(MoveWeightsCollection other)
  {
    for (int i = 0; i < numRoles; i++)
    {
      for (Entry<Move, MoveWeight> e : other.roleMoveWeights[i].entrySet())
      {
        MoveWeight existingValue = roleMoveWeights[i].get(e.getKey());
        if (existingValue == null)
        {
          existingValue = new MoveWeight();
          roleMoveWeights[i].put(e.getKey(), existingValue);
        }
        if (e.getValue().value > existingValue.value)
        {
          existingValue.value = e.getValue().value;
        }
      }
    }
  }

  public void decay()
  {
    for (int i = 0; i < numRoles; i++)
    {
      for (MoveWeight val : roleMoveWeights[i].values())
      {
        val.value *= decayRate;
      }
    }
  }

  public void decayForSelectionThrough(Move move, int roleIndex)
  {
    MoveWeight existingValue = roleMoveWeights[roleIndex].get(move);

    if (existingValue != null)
    {
      existingValue.value *= selectThroughDecayRate;
    }
  }
}