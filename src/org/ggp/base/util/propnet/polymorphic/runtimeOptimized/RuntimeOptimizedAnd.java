package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;


/**
 * The And class is designed to represent logical AND gates.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedAnd extends RuntimeOptimizedComponent implements PolymorphicAnd
{
	private PolymorphicComponent knownFalseInput = null;
	
	public RuntimeOptimizedAnd(int numInputs, int numOutput) {
		super(numInputs, numOutput);
	}

	/**
	 * Returns true if and only if every input to the and is true.
	 * 
	 * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
	 */
	@Override
	protected boolean getValueInternal()
	{
		boolean dirtyFound = false;
		
		knownFalseInput = null;
		//	See if we can find a result without further queries first by checking
		//	non-dirty inputs
		for ( RuntimeOptimizedComponent component : inputsArray )
		{
			if ( !component.isDirty() )
			{
				if (!component.getValue())
				{
					knownFalseInput = component;
					return false;
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
				if ( !component.getValue() )
				{
					knownFalseInput = component;
					return false;
				}
			}
		}

		return true;
	}
	
	@Override
    public void reset(boolean disable)
	{
		super.reset(disable);
		knownFalseInput = null;
	}
	
	void reFindKnownFalse()
	{
		knownFalseInput = null;
		
		for(RuntimeOptimizedComponent input : inputsArray)
		{
			if ( !input.isDirty() && !input.getValue())
			{
				knownFalseInput = input;
				break;
			}
		}
	}

	@Override
    public void setDirty(boolean from, PolymorphicComponent source)
    {
		if ( !dirty )
		{
			if ( source == knownFalseInput )
    		{
				reFindKnownFalse();
    		}
       	   	
       	   	if ( null == knownFalseInput )
    		{
		    	dirty = true;
		    	
	    		for(RuntimeOptimizedComponent output : outputsArray)
	    		{
	    			output.setDirty(cachedValue, this);
	    		}
    		}
		}
    }

	/**
	 * @see org.ggp.base.util.propnet.architecture.Component#toString()
	 */
	@Override
	public String toString()
	{
		return toDot("invhouse", "grey", "AND");
	}

}
