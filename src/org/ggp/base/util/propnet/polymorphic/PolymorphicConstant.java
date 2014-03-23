
package org.ggp.base.util.propnet.polymorphic;


// Base class for all non-abstract And implementations. Need so that
// instanceof PolymorphicConstant can be used regardless of the concrete class
// hierarchy produced by the factory
public abstract interface PolymorphicConstant extends PolymorphicComponent
{
}
