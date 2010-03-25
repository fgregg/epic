package scalanlp.parser

import scalanlp.collection.mutable.TriangularArray
import scalanlp.trees.BinarizedTree
import scalanlp.trees.BinaryTree
import scalanlp.trees.NullaryTree
import scalanlp.trees.UnaryTree
import scalanlp.trees.Span
import scalanlp.util.IntBloomFilter

final class ParseChart[L](grammar: Grammar[L], length: Int) {
  private class BackPtr;
  private case object Term extends BackPtr;
  private case class Unary(lchild: Int) extends BackPtr;
  private case class Binary(lchild: Int, rchild: Int, split: Int) extends BackPtr;

  private val score = new TriangularArray(length+1, grammar.mkVector(Double.NegativeInfinity));
  private val back = new TriangularArray(length+1, grammar.mkSparseArray[BackPtr]);
  private val active = new TriangularArray(length+1, new IntBloomFilter(grammar.index.size,3));

  def enterTerm(begin: Int, end: Int, label: L, w: Double): Unit = enterTerm(begin,end,grammar.index(label),w);
  def enterTerm(begin: Int, end: Int, label: Int, w: Double): Unit = {
    score(begin, end)(label) = w;
    back(begin, end)(label) = Term;
    active(begin, end) += label
  }

  def enterUnary(begin: Int, end: Int, parent: L, child: L, w: Double): Unit = {
    enterUnary(begin,end,grammar.index(parent),grammar.index(child),w);
  }
  def enterUnary(begin: Int, end: Int, parent: Int, child: Int, w: Double): Unit = {
    score(begin, end)(parent) = w;
    back(begin, end)(parent) = Unary(child);
    active(begin, end) += parent;
  }

  def enterBinary(begin: Int, split: Int, end: Int, parent: L, lchild: L, rchild: L, w: Double): Unit = {
    enterBinary(begin,split,end,grammar.index(parent),grammar.index(lchild), grammar.index(rchild), w);
  }
  def enterBinary(begin: Int, split: Int, end: Int, parent: Int, lchild: Int, rchild: Int, w: Double): Unit = {
    score(begin, end)(parent) = w;
    back(begin, end)(parent) = Binary(lchild,rchild,split);
    active(begin, end) += parent;
  }

  def enteredLabelIndexes(begin: Int, end: Int) = {
    score(begin, end).activeDomain.iterator
  }

  def enteredLabelScores(begin: Int, end: Int) = {
    score(begin, end).activeElements
  }


  def labelScore(begin: Int, end: Int, label: L): Double = labelScore(begin,end,grammar.index(label));
  def labelScore(begin: Int, end: Int, label: Int): Double = {
    if(!active(begin, end)(label)) Double.NegativeInfinity
    else score(begin, end)(label);
  }

  def buildTree(start: Int, end: Int, root: Int):BinarizedTree[L] = {
    val b = back(start, end)(root)
    b match {
      case Term =>
        NullaryTree(grammar.index.get(root))(Span(start,end));
      case Unary(child) =>
        val c = buildTree(start,end,child);
        UnaryTree(grammar.index.get(root),c)(Span(start,end));
      case Binary(lchild,rchild,split) =>
        val lc = buildTree(start,split,lchild);
        val rc = buildTree(split,end,rchild);
        BinaryTree(grammar.index.get(root),lc,rc)(Span(start,end));
    }
  }

}
