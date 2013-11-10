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
	private ForwardDeadReckonLegalMoveSet owningMoveSet = null;
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
	
	public void setCrossReferenceInfo(ForwardDeadReckonPropositionCrossReferenceInfo info)
	{
		opaqueInfo = info;
	}
    
    public void setTransitionSet(ForwardDeadReckonLegalMoveInfo associatedMove, ForwardDeadReckonLegalMoveSet activeLegalMoves)
    {
    	this.owningMoveSet = activeLegalMoves;
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

    public void setKnownChangedState(boolean newState, ForwardDeadReckonComponent source)
    {
		cachedValue = newState;
		
		if ( owningMoveSet != null )
		{
			//ProfileSection methodSection = new ProfileSection("ForwardDeadReckonProposition.stateChange");
			//try
			{
				if ( newState )
				{
					owningMoveSet.add(associatedMove);
				}
				else
				{
					owningMoveSet.remove(associatedMove);
				}				
			}
			//finally
			//{
			//	methodSection.exitScope();
			//}
		}

		if ( queuePropagation )
		{
			queuePropagation();
		}
		else
		{
			propagate();
		}
    }

	/**
	 * Setter method.
	 * 
	 * @param value
	 *            The new value of the Proposition.
	 */
	public void setValue(boolean value)
	{
		if ( cachedValue != value )
		{
			cachedValue = value;
			
    		if ( queuePropagation )
    		{
    			queuePropagation();
    		}
    		else
    		{
    			propagate();
    		}
		}
	}
    
	/**
	 * @see org.ggp.base.util.propnet.architecture.Component#toString()
	 */
	@Override
	public String toString()
	{
		return toDot("circle", cachedValue ? "red" : "white", name.toString());
	}
}