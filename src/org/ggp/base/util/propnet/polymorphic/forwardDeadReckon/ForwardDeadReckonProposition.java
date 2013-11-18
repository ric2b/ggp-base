package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;

/**
 * The Proposition class is designed to represent named latches.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonProposition extends ForwardDeadReckonComponent implements PolymorphicProposition
{
	/** The name of the Proposition. */
	private GdlSentence name;
	private ForwardDeadReckonLegalMoveSet[] owningMoveSet = null;
	private ForwardDeadReckonLegalMoveInfo associatedMove = null;
	private ForwardDeadReckonPropositionCrossReferenceInfo opaqueInfo = null;

	/**
	 * Creates a new Proposition with name <tt>name</tt>.
	 * @param numOutputs 
	 * 
	 * @param name
	 *            The name of the Proposition.
	 */
	public ForwardDeadReckonProposition(int numOutputs, GdlSentence name)
	{
		super(1, numOutputs);
		
		this.name = name;
	}
	
	public ForwardDeadReckonPropositionCrossReferenceInfo getCrossReferenceInfo()
	{
		return opaqueInfo;
	}
    
    @Override
	public void crystalize(int numInstances)
    {
    	super.crystalize(numInstances);
    	
    	owningMoveSet = new ForwardDeadReckonLegalMoveSet[numInstances];
    }
	
	public void setCrossReferenceInfo(ForwardDeadReckonPropositionCrossReferenceInfo info)
	{
		opaqueInfo = info;
	}
    
    public void setTransitionSet(ForwardDeadReckonLegalMoveInfo associatedMove, int instanceId, ForwardDeadReckonLegalMoveSet activeLegalMoves)
    {
    	this.owningMoveSet[instanceId] = activeLegalMoves;
    	this.associatedMove = associatedMove;
    }

	/**
	 * Getter method.
	 * 
	 * @return The name of the Proposition.
	 */
	public GdlSentence getName()
	{
		return name;
	}
	
    /**
     * Setter method.
     * 
     * This should only be rarely used; the name of a proposition
     * is usually constant over its entire lifetime.
     * 
     * @return The name of the Proposition.
     */
    public void setName(GdlSentence newName)
    {
        name = newName;
    }

    public void setKnownChangedState(boolean newState, int instanceId, ForwardDeadReckonComponent source)
    {
		cachedValue[instanceId] = newState;
		
		if ( owningMoveSet[instanceId] != null )
		{
			//ProfileSection methodSection = new ProfileSection("ForwardDeadReckonProposition.stateChange");
			//try
			{
				if ( newState )
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

		if ( queuePropagation )
		{
			queuePropagation(instanceId);
		}
		else
		{
			propagate(instanceId);
		}
    }

	/**
	 * Setter method.
	 * 
	 * @param value
	 *            The new value of the Proposition.
	 */
	public void setValue(boolean value, int instanceId)
	{
		if ( cachedValue[instanceId] != value )
		{
			cachedValue[instanceId] = value;
			
    		if ( queuePropagation )
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
		return toDot("circle", cachedValue[0] ? "red" : "white", name.toString());
	}

	@Override
	public void setValue(boolean value) {
		setValue(value, 0);
	}
}