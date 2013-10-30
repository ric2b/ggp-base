package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Or class is designed to represent logical OR gates.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedOr extends RuntimeOptimizedComponent implements PolymorphicOr
{
	PolymorphicComponent knownTrueInput = null;
	
	public RuntimeOptimizedOr(int numInputs, int numOutputs) {
		super(numInputs, numOutputs);
	}

	/**
	 * Returns true if and only if at least one of the inputs to the or is true.
	 * 
	 * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
	 */
	@Override
	protected boolean getValueInternal()
	{
		boolean dirtyFound = false;
		
		knownTrueInput = null;
		//	See if we can find a result without further queries first by checking
		//	non-dirty inputs
		for ( RuntimeOptimizedComponent component : inputsArray )
		{
			if ( !component.isDirty() )
			{
				if (component.getValue() )
				{
					knownTrueInput = component;
					return true;
				}
			}
			else
			{
				dirtyFound = true;
			}
		}
		
		if (dirtyFound)
		{
			for ( RuntimeOptimizedComponent component : inputsArray )
			{
				if ( component.getValue() )
				{
					knownTrueInput = component;						
					return true;
				}
			}
		}
		
		return false;
	}
	
	void refindKnownTrues()
	{
		knownTrueInput = null;
		
		for(RuntimeOptimizedComponent input : inputsArray)
		{
			if ( !input.isDirty() && input.getValue())
			{
				knownTrueInput = input;
				break;
			}
		}
	}

 	@Override
    public void reset(boolean disable)
	{
		super.reset(disable);
		knownTrueInput = null;
	}

	@Override
    public void setDirty(boolean from, BidirectionalPropagationComponent source)
    {
		if ( !dirty)
		{
    		if ( source == knownTrueInput )
    		{
    			refindKnownTrues();
    		}
    		
       	   	if ( null == knownTrueInput )
    		{
		    	dirty = true;
		    	
	    		for(RuntimeOptimizedComponent output : outputsArray)
	    		{
	    			output.setDirty(cachedValue, this);
	    		}
    		}
		}
    }
	
    public void setKnownChangedState(boolean newState, BidirectionalPropagationComponent source)
    {
		if (newState)
		{
			if ( knownTrueInput == null)
			{
				knownTrueInput = source;
			}
			dirty = false;
			if ( !cachedValue )
			{
				cachedValue = true;
	    		for(RuntimeOptimizedComponent output : outputsArray)
	    		{
	    			output.setKnownChangedState(false, this);
	    		}
			}
			return;
		}
		
		setDirty(false, source);
    }

	/**
	 * @see org.ggp.base.util.propnet.architecture.Component#toString()
	 */
	@Override
	public String toString()
	{
		return toDot("ellipse", "grey", "OR");
	}
}