
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;

/**
 * The Proposition class is designed to represent named latches.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonProposition extends
                                               ForwardDeadReckonComponent
                                                                         implements
                                                                         PolymorphicProposition
{
  /** The name of the Proposition. */
  private GdlSentence                                    name;
  private ForwardDeadReckonLegalMoveSet[]                owningMoveSet  = null;
  private ForwardDeadReckonLegalMoveInfo                 associatedMove = null;
  private ForwardDeadReckonPropositionInfo               opaqueInfo     = null;

  /**
   * Creates a new Proposition with name <tt>name</tt>.
   *
   * @param numOutputs
   * @param name
   *          The name of the Proposition.
   */
  public ForwardDeadReckonProposition(int numOutputs, GdlSentence name)
  {
    super(1, numOutputs);

    this.name = name;
  }

  public ForwardDeadReckonPropositionInfo getInfo()
  {
    return opaqueInfo;
  }

  @Override
  public void crystalize(int numInstances)
  {
    super.crystalize(numInstances);

    owningMoveSet = new ForwardDeadReckonLegalMoveSet[numInstances];
  }

  public void setInfo(ForwardDeadReckonPropositionInfo info)
  {
    opaqueInfo = info;
  }

  public void setTransitionSet(ForwardDeadReckonLegalMoveInfo associatedMove,
                               int instanceId,
                               ForwardDeadReckonLegalMoveSet activeLegalMoves)
  {
    this.owningMoveSet[instanceId] = activeLegalMoves;
    this.associatedMove = associatedMove;
  }

  public ForwardDeadReckonLegalMoveInfo getAssociatedMove()
  {
    return associatedMove;
  }

  /**
   * Getter method.
   *
   * @return The name of the Proposition.
   */
  @Override
  public GdlSentence getName()
  {
    return name;
  }

  /**
   * Setter method. This should only be rarely used; the name of a proposition
   * is usually constant over its entire lifetime.
   *
   * @return The name of the Proposition.
   */
  @Override
  public void setName(GdlSentence newName)
  {
    name = newName;
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   int instanceId,
                                   ForwardDeadReckonComponent source)
  {
    if ( newState )
    {
      state[instanceId] |= cachedStateMask;
    }
    else
    {
      state[instanceId] &= ~cachedStateMask;
    }

    if (owningMoveSet[instanceId] != null)
    {
      //ProfileSection methodSection = new ProfileSection("ForwardDeadReckonProposition.stateChange");
      //try
      {
        if (newState)
        {
          owningMoveSet[instanceId].add(associatedMove);
        }
        else
        {
          owningMoveSet[instanceId].remove(associatedMove);
        }
      }
      //finally
      //{
      //	methodSection.exitScope();
      //}
    }

    if (queuePropagation)
    {
      queuePropagation(instanceId);
    }
    else
    {
      propagate(instanceId);
    }
  }

  @Override
  public void noteNewValue(int instanceId, boolean value)
  {
    if (owningMoveSet[instanceId] != null)
    {
      if (value)
      {
        owningMoveSet[instanceId].add(associatedMove);
      }
      else
      {
        owningMoveSet[instanceId].remove(associatedMove);
      }
    }
  }

  /**
   * Setter method.
   *
   * @param value
   *          The new value of the Proposition.
   */
  public void setValue(boolean value, int instanceId)
  {
    if (((state[instanceId] & cachedStateMask) != 0) != value)
    {
      if ( value )
      {
        state[instanceId] |= cachedStateMask;
      }
      else
      {
        state[instanceId] &= ~cachedStateMask;
      }

      if (queuePropagation)
      {
        queuePropagation(instanceId);
      }
      else
      {
        propagate(instanceId);
      }
    }
  }

  /**
   * @see org.ggp.base.util.propnet.architecture.Component#toString()
   */
  @Override
  public String toString()
  {
    return toDot("circle", (state[0] & cachedStateMask) != 0 ? "red" : "white", name.toString());
  }

  @Override
  public void setValue(boolean value)
  {
    setValue(value, 0);
  }
}