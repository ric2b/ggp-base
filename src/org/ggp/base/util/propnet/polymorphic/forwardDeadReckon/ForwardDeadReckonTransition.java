package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Transition class is designed to represent pass-through gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonTransition extends ForwardDeadReckonComponent implements PolymorphicTransition
{
	private Set<GdlSentence> owningPropositionSet = null;
	private GdlSentence owningProposition = null;
	
	public ForwardDeadReckonTransition(int numOutputs) {
		super(1, numOutputs);
	}

    public void setKnownChangedState(boolean newState, ForwardDeadReckonComponent source)
    {
		cachedValue = newState;
		if ( owningPropositionSet != null )
		{
			if ( newState )
			{
				owningPropositionSet.add(owningProposition);
			}
			else
			{
				owningPropositionSet.remove(owningProposition);
			}
		}
    }
    
    public void setTransitionSet(GdlSentence owningProposition, Set<GdlSentence> owningSet)
    {
    	this.owningPropositionSet = owningSet;
    	this.owningProposition = owningProposition;
    }
    
	/**
	 * @see org.ggp.base.util.propnet.architecture.Component#toString()
	 */
	@Override
	public String toString()
	{
		return toDot("box", "grey", "TRANSITION");
	}
}