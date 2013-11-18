package org.ggp.base.util.propnet.polymorphic;

public interface MultiInstanceComponent {
    /**
     * Gets the value of the Component.
     * 
     * @return The value of the Component.
     */
    public abstract boolean getValue(int instanceIndex);
	
	public abstract void crystalize(int numInstances);
}
