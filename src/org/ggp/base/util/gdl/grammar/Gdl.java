
package org.ggp.base.util.gdl.grammar;

import java.io.Serializable;

/**
 * Class at the root of the GDL hierarchy.  All parts of a game's GDL are represented by objects that are part of this
 * hierarchy.
 *
 * See <a href="http://alloyggp.blogspot.co.uk/2013/02/a-quick-guide-to-gdl-terminology.html">A quick guide to GDL
 * terminology</a>.
 *
 * <h1>The GDL hierarchy</h1>
 *
 * <ul>
 *
 * <li><b>Rule</b>: A rule in a GDL description starts with the <= operator, follows it with a <i>sentence</i> (whatever
 *     is implied by the <i>rule</i>) known as the head and then has a body consisting of <i>literals</i>.
 *
 * <li><b>Literal</b>: A literal is a condition for a <i>rule</i>.  This can be a <i>sentence</i>, a <i>not</i>, a
 *     declaration that two terms are <i>distinct</i>, or a <i>disjunction</i> of other <i>literals</i>.<ul>
 *
 *   <li><b>Sentence</b>: A sentence is something that can be true or false on a given turn of a game.  It is made up of
 *       a name (which is a <i>constant</i>) and (optionally) a body consisting of <i>terms</i>.  There are two types
 *       of sentence depending on whether or not it has a body.<ul>
 *
 *     <li><b>Proposition</b>: A <i>sentence</i> with no body is called a <i>proposition</i>.  (Note that this is
 *         different from the sense of "proposition" used in propositional network, which is equivalent to a
 *         <i>sentence</i> in this terminology.)
 *
 *     <li><b>Relation</b>: A <i>sentence</i> with a body is called a <i>relation</i>.</ul>
 *
 *   <li><b>Not</b>: A negated <i>literal</i>.
 *
 *   <li><b>Distinct</b>: A declaration that two <i>terms</i> are distinct.
 *
 *   <li><b>Or</b> (deprecated): A disjunction of <i>literals</i>.  This should no longer appear in valid GDL but is
 *       maintained for back-compatibility.</ul>
 *
 * <li><b>Term</b>: A term is a <i>constant</i>, <i>variable</i>, or <i>function</i>.  It's what fills one "slot" in the
 *     body of a <i>sentence</i>.<ul>
 *
 *   <li><b>Constant</b>: A constant is a "word" of the language for a particular game, or one of the GDL keywords. If
 *       it's a single word without parentheses (and not a variable) it's a <i>constant</i>.
 *
 *   <li><b>Variable</b>: A "word" in a sentence starting with ?. It can represent a <i>constant</i> or a
 *       <i>function</i>.
 *
 *   <li><b>Function</b>: A complex <i>term</i> that contains other <i>terms</i>. It has a name (which is a
 *       <i>constant</i>) and a body consisting of other <i>terms</i>, much like a <i>sentence</i>.  Unlike a
 *       <i>sentence</i>, however, it does not have a truth value.</ul>
 *
 * </ul>
 *
 * <h1>Other terms</h1>
 *
 * <ul>
 *   <li><b>Arity</b>: The number of terms in a sentence body or function body. This should be constant across every use
 *       of a sentence name or function name.
 *   <li><b>Ground</b>: A GDL statement is in <i>ground</i> form if it has no <i>variables</i>.  <i>Rules</i> are in
 *       ground form provided that the body is variable free.
 * </ul>
 *
 * The rules of a game are usually represented as {@code List<Gdl>}.  However, the only two objects which can appear at
 * the top level are <i>sentences</i> (which are always true in all turns of the game) and <i>rules</i> (which specify
 * <i>sentences</i> that are conditionally true and will usually be true in some game states but false in others).
 *
 * <h1>Worked example</h1>
 *
 * Consider the following example from the GDL for Kalah.
 *
 * <p><pre>{@code
 * (<= (next (pit ?p ?n))
 *   (does ?r (put ?p2))
 *   (true (pit ?p ?n))
 *   (distinct ?p ?p2)
 *   (not (true (hand ?r s1))))}</pre>
 *
 *
 * <p>The whole thing is a <i>rule</i>.
 * <ul>
 *   <li>The <i>relation</i> <code>(next (pit ?p ?n))</code> has arity 1 and is the head of the <i>rule</i>.
 *   <ul>
 *     <li>The <i>constant</i> <code>next</code> is the name of the <i>relation</i>.
 *     <li><code>(pit ?p ?n)</code> is a <i>function</i> of arity 2.  It is the single <i>term</i> which makes up the
 *         body of the <i>relation</i>.
 *     <ul>
 *       <li>The <i>constant</i> <code>pit</code> is the name of the <i>function</i>.
 *       <li><code>?p</code> is a <i>variable</i>.
 *       <li><code>?n</code> is a <i>variable</i>.
 *     </ul>
 *   </ul>
 *   <li>The remaining lines are <i>literals</i> which make up the body of the <i>rule</i>.
 *   <ul>
 *     <li><code>(does ?r (put ?p2))</code> is a <i>relation</i> of arity 2.
 *     <ul>
 *       <li><code>does</code> is the name of the <i>relation</i>.  It is a <i>constant</i>.
 *       <li><code>?r (put ?p2)</code> are the <i>terms</i> which make up the body of the <i>relation</i>.
 *       <ul>
 *         <li><code>?r</code> is a <i>variable</i>.
 *         <li><code>(put ?p2)</code> is a <i>function</i> of arity 1.
 *         <ul>
 *           <li><code>put</code> is the name of the <i>function</i> and is a <i>constant</i>.
 *           <li><code>?p2</code> is a <i>variable</i>.
 *         </ul>
 *       </ul>
 *     </ul>
 *     <li><code>(true (pit ?p ?n))</code> is a <i>relation</i> of arity 1.
 *     <ul>
 *       <li>The <i>constant</i> <code>true</code> is the name of the <i>relation</i>.
 *       <li><code>(pit ?p ?n)</code> is a <i>function</i> of arity 2.  It is the single <i>term</i> which makes up the
 *            body of the <i>relation</i>.
 *       <ul>
 *         <li>The <i>constant</i> <code>pit</code> is the name of the <i>function</i>.
 *         <li><code>?p</code> is a <i>variable</i>.
 *         <li><code>?n</code> is a <i>variable</i>.
 *       </ul>
 *     </ul>
 *     <li><code>(distinct ?p ?p2)</code> is a <i>distinct</i>.  The two terms making up the <i>distinct</i> are...
 *     <ul>
 *       <li><code>?p</code> is a <i>variable</i>.
 *       <li><code>?p2</code> is a <i>variable</i>.
 *     </ul>
 *     <li><code>(not (true (hand ?r s1)))</code> is a <i>not</i> (or <i>negated literal</i>).
 *     <ul>
 *       <li><code>(true (hand ?r s1))</code> is a <i>relation</i>.  See <code>(true (pit ?p ?n))</code> above for how
 *           this is broken down, but note that <code>s1</code> is a <i>constant</i> (whilst <code>?n</code> is a
 *           <i>literal</i>).
 *     </ul>
 *   </ul>
 * </ul>
 *
 */
@SuppressWarnings("serial")
public abstract class Gdl implements Serializable
{
  /**
   * @return whether this GDL statement is in ground form - i.e. variable-free (except for GdlRule which only needs to
   *         be variable-free in the body).
   */
  public abstract boolean isGround();

  @Override
  public abstract String toString();

  /**
   * This method is used by deserialization to ensure that Gdl objects loaded
   * from an ObjectInputStream or a remote method invocation are the versions
   * that exist in the GdlPool.
   */
  protected Object readResolve()
  {
    return GdlPool.immerse(this);
  }

}
