package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Transition class is designed to represent pass-through gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonTransition extends ForwardDeadReckonComponent implements PolymorphicTransition
{
	private ForwardDeadReckonInternalMachineState owningTransitionInfoSet = null;
	private ForwardDeadReckonPropositionCrossReferenceInfo transitionInfo = null;
	
	public ForwardDeadReckonTransition(int numOutputs) {
		super(1, numOutputs);
	}

    public void setKnownChangedState(boolean newState, ForwardDeadReckonComponent source)
    {
		cachedValue = newState;
		if ( owningTransitionInfoSet != null )
		{
			//ProfileSection methodSection = new ProfileSection("ForwardDeadReckonTransition.legalStateChange");
			//try
			{
				if ( newState )
				{
					owningTransitionInfoSet.add(transitionInfo);
				}
				else
				{
					owningTransitionInfoSet.remove(transitionInfo);
				}
			}
			//finally
			//{
			//	methodSection.exitScope();
			//}
		}
    }
    
    public void setTransitionSet(ForwardDeadReckonPropositionCrossReferenceInfo transitionInfo, ForwardDeadReckonInternalMachineState owningSet)
    {
    	this.owningTransitionInfoSet = owningSet;
    	this.transitionInfo = transitionInfo;
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