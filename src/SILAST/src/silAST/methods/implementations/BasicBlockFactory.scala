package silAST.methods.implementations

import silAST.programs.NodeFactory
import silAST.source.SourceLocation
import silAST.types.DataType
import silAST.programs.symbols.{ProgramVariableSequence, Field, ProgramVariable}
import silAST.expressions.util.PTermSequence
import collection.Set
import silAST.methods.MethodFactory
import silAST.expressions.{PredicateExpression, PExpression, Expression, ExpressionFactory}
import silAST.expressions.terms.PTerm
import silAST.programs.ScopeFactory
import collection.mutable.{ListBuffer, HashSet}


class BasicBlockFactory private[silAST](
                                         private val implementationFactory: ImplementationFactory,
                                         val sl: SourceLocation,
                                         val name: String
                                         ) extends NodeFactory with ExpressionFactory with ScopeFactory {
  //////////////////////////////////////////////////////////////////
  def compile(): BasicBlock = {
    basicBlock.cfg = implementationFactory.cfg
    basicBlock
  }

  //////////////////////////////////////////////////////////////////
  def addProgramVariableToScope(v : ProgramVariable)
  {
    require (!(localVariables contains v))
    require( implementationFactory.localVariables contains v)
    basicBlock.pLocalVariables.append(v)
  }

  //////////////////////////////////////////////////////////////////
  def appendAssignment(
                        sl: SourceLocation,
                        target: ProgramVariable,
                        source: PTerm
                        ) = {
    require((localVariables contains target)  || (results contains target)) //no writing to inputs
    require(terms contains source)
    basicBlock.appendStatement(new AssignmentStatement(sl, target, source))
  }

  //////////////////////////////////////////////////////////////////
  def appendFieldAssignment(
                             sl: SourceLocation,
                             target: ProgramVariable,
                             field: Field,
                             source: PTerm
                             ) {
    require(programVariables contains target)
    require(fields contains field)
    require(terms contains source)

    basicBlock.appendStatement(new FieldAssignmentStatement(sl, target, field, source))
  }

  //////////////////////////////////////////////////////////////////
  def appendNew(
                          sl: SourceLocation,
                          target: ProgramVariable,
                          dataType: DataType
                          ) {
    require(localVariables contains target)
    require(dataTypes contains dataType)

    basicBlock.appendStatement(new NewStatement(sl, target, dataType))
  }

  //////////////////////////////////////////////////////////////////
  def makeProgramVariableSequence(sl: SourceLocation, vs: Seq[ProgramVariable]): ProgramVariableSequence = {
    require(vs.forall(programVariables contains _))
    val result = new ProgramVariableSequence(sl, vs)
    programVariableSequences += result
    result
  }

  //////////////////////////////////////////////////////////////////
  def appendCall(
                           sl: SourceLocation,
                           targets: ProgramVariableSequence,
                           receiver: PTerm,
                           methodFactory: MethodFactory,
                           arguments: PTermSequence
                           ) {
    require(programVariableSequences contains targets)
    require(targets.forall(localVariables contains _))
    require(terms contains receiver)
    require(methodFactories contains methodFactory)
    require(arguments.forall( terms contains _))

    basicBlock.appendStatement(new CallStatement(sl, targets, receiver, methodFactory.method, arguments))
  }

  //////////////////////////////////////////////////////////////////
  def appendInhale(
                    sl: SourceLocation,
                    e: Expression
                    ) {
    require(expressions contains e)

    basicBlock.appendStatement(new InhaleStatement(sl, e))
  }

  //////////////////////////////////////////////////////////////////
  def appendExhale(
                    sl: SourceLocation,
                    e: Expression
                    ) {
    require(expressions contains e)

    basicBlock.appendStatement(new ExhaleStatement(sl, e))
  }

  //////////////////////////////////////////////////////////////////
  def appendFold(
                  sl: SourceLocation,
                  e: PredicateExpression
                  ) {
    require(expressions contains e)

    basicBlock.appendStatement(new FoldStatement(sl, e))
  }

  //////////////////////////////////////////////////////////////////
  def appendUnfold(
                    sl: SourceLocation,
                    e: PredicateExpression
                    ) {
    require(expressions contains e)

    basicBlock.appendStatement(new UnfoldStatement(sl, e))
  }

  //////////////////////////////////////////////////////////////////
  def addSuccessor(sl: SourceLocation, successor: BasicBlockFactory, condition: Expression, isBackEdge: Boolean = false) = {
    require(basicBlock.successors.forall(_.target != successor.basicBlock))
    new CFGEdge(sl, basicBlock, successor.basicBlock, condition, isBackEdge)
  }

  //////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////
  override val parentFactory = Some(implementationFactory)
  
  override def fields: Set[Field] = implementationFactory.fields

//  private val scopedVariables = new ListBuffer[ProgramVariable];

  def localVariables = basicBlock.localVariables; //scopedVariables;

  def results = implementationFactory.results

  private def parameters = implementationFactory.parameters

  override def programVariables: Set[ProgramVariable] =
    localVariables union parameters.toSet[ProgramVariable]

//  override def functions = implementationFactory.functions

  val programVariableSequences = new HashSet[ProgramVariableSequence]

  protected[silAST] override def dataTypes = implementationFactory.dataTypes union pDataTypes

  override def typeVariables = Set()

  private[silAST] lazy val basicBlock: BasicBlock = new BasicBlock(sl, name)
}