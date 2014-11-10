// -*- tab-width:2 ; indent-tabs-mode:nil -*-

package vct.main;

import hre.ast.FileOrigin;
import hre.config.BooleanSetting;
import hre.config.OptionParser;
import hre.config.StringListSetting;
import hre.config.StringSetting;
import hre.debug.HeapDump;
import hre.io.PrefixPrintStream;
import hre.util.CompositeReport;
import hre.util.TestReport;

import java.io.*;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import vct.clang.printer.CPrinter;
import vct.col.annotate.DeriveModifies;
import vct.col.ast.*;
import vct.col.rewrite.AbstractMethodResolver;
import vct.col.rewrite.AssignmentRewriter;
import vct.col.rewrite.BoxingRewriter;
import vct.col.rewrite.CSLencoder;
import vct.col.rewrite.ChalicePreProcess;
import vct.col.rewrite.CheckHistoryAlgebra;
import vct.col.rewrite.CheckProcessAlgebra;
import vct.col.rewrite.ClassConversion;
import vct.col.rewrite.ConstructorRewriter;
import vct.col.rewrite.DefineDouble;
import vct.col.rewrite.DynamicStaticInheritance;
import vct.col.rewrite.ExplicitPermissionEncoding;
import vct.col.rewrite.FilterClass;
import vct.col.rewrite.FinalizeArguments;
import vct.col.rewrite.Flatten;
import vct.col.rewrite.FlattenBeforeAfter;
import vct.col.rewrite.ForkJoinCompilation;
import vct.col.rewrite.GenericPass1;
import vct.col.rewrite.GhostLifter;
import vct.col.rewrite.GlobalizeStaticsField;
import vct.col.rewrite.GlobalizeStaticsParameter;
import vct.col.rewrite.InheritanceRewriter;
import vct.col.rewrite.InlinePredicatesRewriter;
import vct.col.rewrite.IterationContractEncoder;
import vct.col.rewrite.KernelRewriter;
import vct.col.rewrite.RandomizedIf;
import vct.col.rewrite.ReorderAssignments;
import vct.col.rewrite.RewriteArray;
import vct.col.rewrite.RewriteArrayPerms;
import vct.col.rewrite.RewriteArrayRef;
import vct.col.rewrite.SatCheckRewriter;
import vct.col.rewrite.SilverClassReduction;
import vct.col.rewrite.SilverConstructors;
import vct.col.rewrite.SilverReorder;
import vct.col.rewrite.SimplifyCalls;
import vct.col.rewrite.SimplifyExpressions;
import vct.col.rewrite.Standardize;
import vct.col.rewrite.StripConstructors;
import vct.col.rewrite.VoidCalls;
import vct.col.rewrite.WandEncoder;
import vct.col.util.ASTFactory;
import vct.col.util.FeatureScanner;
import vct.col.util.SimpleTypeCheck;
import vct.java.printer.JavaDialect;
import vct.java.printer.JavaSyntax;
import vct.util.ClassName;
import vct.util.Configuration;
import static hre.System.*;

/**
 * VerCors Tool main verifier.
 * @author Stefan Blom
 *
 */
public class Main
{
  private static ProgramUnit program=new ProgramUnit();
  
  private static void parseFile(String name){
    int dot=name.lastIndexOf('.');
    if (dot<0) {
      Fail("cannot deduce language of %s",name);
    }
    String lang=name.substring(dot+1);
    if (lang.equals("pvl")){
      //TODO: fix this kludge.
      vct.col.ast.ASTNode.pvl_mode=true;
    }
    Progress("Parsing %s file %s",lang,name);
    ProgramUnit unit=Parsers.getParser(lang).parse(new File(name));
    Progress("Read %s succesfully",name);
    program.add(unit);
    Progress("Merged %s succesfully",name);
  }

  private static List<ClassName> classes;
  
  static class ChaliceTask implements Callable<TestReport> {
    private ClassName class_name;
    private ProgramUnit program;

    public ChaliceTask(ProgramUnit program,ClassName class_name){
      this.program=program;
      this.class_name=class_name;
    }
    @Override
    public TestReport call() {
      System.err.printf("Validating class %s...%n",class_name.toString("."));
      long start=System.currentTimeMillis();
      ProgramUnit task=new FilterClass(program,class_name.name).rewriteAll();
      task=new Standardize(task).rewriteAll();
      new SimpleTypeCheck(task).check();
      TestReport report=vct.boogie.Main.TestChalice(task);
      System.err.printf("%s: result is %s (%dms)%n",class_name.toString("."),
          report.getVerdict(),System.currentTimeMillis()-start);
      return report;
    }
    
  }

  public static void main(String[] args) throws Throwable
  {
    long globalStart = System.currentTimeMillis();
    OptionParser clops=new OptionParser();
    clops.add(clops.getHelpOption(),'h',"help");

    
    BooleanSetting boogie=new BooleanSetting(false);
    clops.add(boogie.getEnable("select Boogie backend"),"boogie");
    BooleanSetting chalice=new BooleanSetting(false);
    clops.add(chalice.getEnable("select Chalice backend"),"chalice");
    BooleanSetting chalice2sil=new BooleanSetting(false);
    clops.add(chalice2sil.getEnable("select Silicon backend via chalice2sil"),"chalice2sil");
    final StringSetting silver=new StringSetting("silicon");
    clops.add(silver.getAssign("select Silver backend (silicon/carbon)"),"silver");
    BooleanSetting verifast=new BooleanSetting(false);
    clops.add(verifast.getEnable("select Verifast backend"),"verifast");
    BooleanSetting dafny=new BooleanSetting(false);
    clops.add(dafny.getEnable("select Dafny backend"),"dafny");
    CommandLineTesting.add_options(clops);

    final BooleanSetting check_defined=new BooleanSetting(false);
    clops.add(check_defined.getEnable("check if defined processes satisfy their contracts."),"check-defined");
    final BooleanSetting check_history=new BooleanSetting(false);
    clops.add(check_history.getEnable("check if defined processes satisfy their contracts."),"check-history");
    final BooleanSetting check_csl=new BooleanSetting(false);
    clops.add(check_csl.getEnable("convert CSL syntax into plain SL"),"check-csl");
    
    
    final BooleanSetting separate_checks=new BooleanSetting(false);
    clops.add(separate_checks.getEnable("validate classes separately"),"separate");
    BooleanSetting help_passes=new BooleanSetting(false);
    clops.add(help_passes.getEnable("print help on available passes"),"help-passes");
    BooleanSetting sequential_spec=new BooleanSetting(false);
    clops.add(sequential_spec.getEnable("sequential specification instead of concurrent"),"sequential");
    StringListSetting pass_list=new StringListSetting();
    clops.add(pass_list.getAppendOption("add to the custom list of compilation passes"),"passes");
    StringListSetting show_before=new StringListSetting();
    clops.add(show_before.getAppendOption("Show source code before given passes"),"show-before");
    StringListSetting show_after=new StringListSetting();
    clops.add(show_after.getAppendOption("Show source code after given passes"),"show-after");
    StringSetting show_file=new StringSetting(null);
    clops.add(show_file.getAssign("redirect show output to files instead of stdout"),"save-show");
    StringListSetting stop_after=new StringListSetting();
    clops.add(stop_after.getAppendOption("Stop after given passes"),"stop-after");
    
    
    BooleanSetting explicit_encoding=new BooleanSetting(false);
    clops.add(explicit_encoding.getEnable("explicit encoding"),"explicit");
    BooleanSetting inline_predicates=new BooleanSetting(false);
    clops.add(inline_predicates.getEnable("inline predicates with arguments"),"inline");
    BooleanSetting global_with_field=new BooleanSetting(false);
    clops.add(global_with_field.getEnable("Encode global access with a field rather than a parameter. (expert option)"),"global-with-field");
    
    BooleanSetting infer_modifies=new BooleanSetting(false);
    clops.add(infer_modifies.getEnable("infer modifies clauses"),"infer-modifies");
    BooleanSetting no_context=new BooleanSetting(false);
    clops.add(no_context.getEnable("disable printing the context of errors"),"no-context");
    
    StringListSetting debug_list=new StringListSetting();
    clops.add(debug_list.getAppendOption("print debug message for given classes and/or packages"),"debug");
    BooleanSetting where=new BooleanSetting(false);
    clops.add(where.getEnable("report which class failed"),"where");
    
    BooleanSetting progress=new BooleanSetting(false);
    clops.add(progress.getEnable("print progress messages"),"progress");
    
    BooleanSetting sat_check=new BooleanSetting(true);
    clops.add(sat_check.getDisable("Disable checking if method pre-conditions are satisfiable"), "disable-sat");
    
    Configuration.add_options(clops);
    
    String input[]=clops.parse(args);
    hre.System.setProgressReporting(progress.get());
    
    for(String name:debug_list){
      hre.System.EnableDebug(name,java.lang.System.err,"vct("+name+")");
    }
    hre.System.EnableWhere(where.get());

    Hashtable<String,CompilerPass> defined_passes=new Hashtable<String,CompilerPass>();
    Hashtable<String,ValidationPass> defined_checks=new Hashtable<String,ValidationPass>();
    defined_passes.put("java",new CompilerPass("print AST in java syntax"){
        public ProgramUnit apply(ProgramUnit arg){
          JavaSyntax.getJava(JavaDialect.JavaVerCors).print(System.out,arg);
          return arg;
        }
      });
    defined_passes.put("c",new CompilerPass("print AST in C syntax"){
        public ProgramUnit apply(ProgramUnit arg){
          vct.clang.printer.CPrinter.dump(System.out,arg);
          return arg;
        }
      });
    defined_passes.put("dump",new CompilerPass("dump AST"){
      public ProgramUnit apply(ProgramUnit arg){
        PrefixPrintStream out=new PrefixPrintStream(System.out);
        HeapDump.tree_dump(out,arg,ASTNode.class);
        return arg;
      }
    });
    defined_passes.put("abstract-resolve",new CompilerPass("convert abstract methods to assume false bodies"){
      public ProgramUnit apply(ProgramUnit arg){
        return new AbstractMethodResolver(arg).rewriteAll();
      }
    });
    defined_passes.put("assign",new CompilerPass("change inline assignments to statements"){
      public ProgramUnit apply(ProgramUnit arg){
        return new AssignmentRewriter(arg).rewriteAll();
      }
    });
    defined_checks.put("boogie",new ValidationPass("verify with Boogie"){
      public TestReport apply(ProgramUnit arg){
        return vct.boogie.Main.TestBoogie(arg);
      }
    });
    defined_checks.put("dafny",new ValidationPass("verify with Dafny"){
      public TestReport apply(ProgramUnit arg){
        return vct.boogie.Main.TestDafny(arg);
      }
    });
    defined_checks.put("silicon-chalice",new ValidationPass("verify Chalice code with Silicon"){
      public TestReport apply(ProgramUnit arg){
        return vct.boogie.Main.TestSilicon(arg);
      }
    });
    defined_checks.put("silver",new ValidationPass("verify input with Silver"){
      public TestReport apply(ProgramUnit arg){
        return example.Main.TestSilicon(arg,silver.get());
      }
    });
    defined_passes.put("box",new CompilerPass("box class types with parameters"){
        public ProgramUnit apply(ProgramUnit arg){
          return new BoxingRewriter(arg).rewriteAll();
        }
      });
    defined_checks.put("chalice",new ValidationPass("verify with Chalice"){
      public TestReport apply(ProgramUnit arg){
        if (separate_checks.get()) {
          long start=System.currentTimeMillis();
          CompositeReport res=new CompositeReport();
          ExecutorService queue=Executors.newFixedThreadPool(4);
          ArrayList<Future<TestReport>> list=new ArrayList<Future<TestReport>>();
          for(ClassName class_name:arg.classNames()){
              Callable<TestReport> task=new ChaliceTask(arg,class_name);
              System.err.printf("submitting verification of %s%n",class_name.toString("."));
              list.add(queue.submit(task));
          }
          queue.shutdown();
          for(Future<TestReport> future:list){
            try {
              res.addReport(future.get());
            } catch (InterruptedException e) {
              // TODO Auto-generated catch block
              e.printStackTrace();
              Abort("%s",e);
            } catch (ExecutionException e) {
              // TODO Auto-generated catch block
              e.printStackTrace();
              Abort("%s",e);
            }
          }
          System.err.printf("verification took %dms%n", System.currentTimeMillis()-start);
          return res;
        } else {
          long start=System.currentTimeMillis();
          TestReport report=vct.boogie.Main.TestChalice(arg);
          System.err.printf("verification took %dms%n", System.currentTimeMillis()-start);
          return report;
        }
      }
    });
    defined_passes.put("check",new CompilerPass("run a type check"){
      public ProgramUnit apply(ProgramUnit arg){
        new SimpleTypeCheck(arg).check();
        return arg;
      }
    });
    defined_passes.put("check-defined",new CompilerPass("rewrite process algebra class to check if defined process match their contracts"){
      public ProgramUnit apply(ProgramUnit arg){
        ProgramUnit tmp=new CheckProcessAlgebra(arg).rewriteAll();
        return new RandomizedIf(tmp).rewriteAll();
      }
    });
    defined_passes.put("check-history",new CompilerPass("rewrite process algebra class to check if history accounting is correct"){
      public ProgramUnit apply(ProgramUnit arg){
        ProgramUnit tmp=new CheckHistoryAlgebra(arg).rewriteAll();
        return new RandomizedIf(tmp).rewriteAll();
      }
    });
    defined_passes.put("csl-encode",new CompilerPass("Encode CSL atomic regions with methods"){
      public ProgramUnit apply(ProgramUnit arg){
        return new CSLencoder(arg).rewriteAll();
      }
    });
    defined_passes.put("class-conversion",new CompilerPass("Convert classes into records and procedures"){
      public ProgramUnit apply(ProgramUnit arg){
        return new ClassConversion(arg).rewriteAll();
      }
    });
    defined_passes.put("define_double",new CompilerPass("Rewrite double as a non-native data type."){
      public ProgramUnit apply(ProgramUnit arg){
        return DefineDouble.rewrite(arg);
      }
    });
    defined_passes.put("erase",new CompilerPass("Erase generic types"){
      public ProgramUnit apply(ProgramUnit arg){
        arg=new GenericPass1(arg).rewriteAll();
        return arg;
      }
    });   
    defined_passes.put("explicit_encoding",new CompilerPass("encode required and ensured permission as ghost arguments"){
      public ProgramUnit apply(ProgramUnit arg){
        return new ExplicitPermissionEncoding(arg).rewriteAll();
      }
    });
    defined_passes.put("finalize_args",new CompilerPass("???"){
      public ProgramUnit apply(ProgramUnit arg){
        return new FinalizeArguments(arg).rewriteAll();
      }
    });
    defined_passes.put("flatten",new CompilerPass("remove nesting of expression"){
      public ProgramUnit apply(ProgramUnit arg){
        return new Flatten(arg).rewriteAll();
      }
    });
    defined_passes.put("ghost-lift",new CompilerPass("Lift ghost code to real code"){
      public ProgramUnit apply(ProgramUnit arg){
        return new GhostLifter(arg).rewriteAll();
      }
    });
    if (global_with_field.get()){
      Warning("Using the incomplete and experimental field access for globals.");
      defined_passes.put("globalize",new CompilerPass("split classes into static and dynamic parts"){
        public ProgramUnit apply(ProgramUnit arg){
          return new GlobalizeStaticsField(arg).rewriteAll();
        }
      });      
    } else {
      defined_passes.put("globalize",new CompilerPass("split classes into static and dynamic parts"){
        public ProgramUnit apply(ProgramUnit arg){
          return new GlobalizeStaticsParameter(arg).rewriteAll();
        }
      });
    }
    defined_passes.put("inheritance",new CompilerPass("rewrite contracts to reflect inheritance - old"){
      public ProgramUnit apply(ProgramUnit arg){
        return new InheritanceRewriter(arg).rewriteOrdered();
      }
    });
    defined_passes.put("ds_inherit",new CompilerPass("rewrite contracts to reflect inheritance, predicate chaining"){
      public ProgramUnit apply(ProgramUnit arg){
        return new DynamicStaticInheritance(arg).rewriteOrdered();
      }
    });
    defined_passes.put("flatten_before_after",new CompilerPass("move before/after instructions"){
      public ProgramUnit apply(ProgramUnit arg){
        return new FlattenBeforeAfter(arg).rewriteAll();
      }
    });
    defined_passes.put("forkjoin",new CompilerPass("compile fork/join statements"){
      public ProgramUnit apply(ProgramUnit arg){
        return new ForkJoinCompilation(arg).rewriteAll();
      }
    });
    defined_passes.put("inline",new CompilerPass("Inline predicates with arguments"){
      public ProgramUnit apply(ProgramUnit arg){
        return new InlinePredicatesRewriter(arg).rewriteAll();
      }
    });
    defined_passes.put("iter",new CompilerPass("Encode iteration contracts as method calls"){
      public ProgramUnit apply(ProgramUnit arg){
        return new IterationContractEncoder(arg).rewriteAll();
      }
    });
    defined_passes.put("kernel-split",new CompilerPass("Split kernels into main, thread and barrier."){
      public ProgramUnit apply(ProgramUnit arg){
        return new KernelRewriter(arg).rewriteAll();
      }
    });
    defined_passes.put("magicwand",new CompilerPass("Encode magic wand proofs with witnesses"){
        public ProgramUnit apply(ProgramUnit arg){
          return new WandEncoder(arg).rewriteAll();
        }
      });
    defined_passes.put("modifies",new CompilerPass("Derive modifies clauses for all contracts"){
        public ProgramUnit apply(ProgramUnit arg){
          new DeriveModifies().annotate(arg);
          return arg;
        }
      });
    defined_passes.put("recognize_arrays",new CompilerPass("standardize array permissions"){
      public ProgramUnit apply(ProgramUnit arg){
        return new RewriteArrayPerms(arg).rewriteAll();
      }
    });
    defined_passes.put("ref_array",new CompilerPass("rewrite array as a sequence of Refs"){
      public ProgramUnit apply(ProgramUnit arg){
        return new RewriteArrayRef(arg).rewriteAll();
      }
    });
    defined_passes.put("reorder",new CompilerPass("reorder statements (e.g. all declarations at the start of a bock"){
      public ProgramUnit apply(ProgramUnit arg){
        return new ReorderAssignments(arg).rewriteAll();
      }
    });
    defined_passes.put("rewrite_arrays",new CompilerPass("rewrite arrays to sequences of cells"){
      public ProgramUnit apply(ProgramUnit arg){
        return new RewriteArray(arg).rewriteAll();
      }
    });
    defined_passes.put("rm_cons",new CompilerPass("???"){
      public ProgramUnit apply(ProgramUnit arg){
        return new ConstructorRewriter(arg).rewriteAll();
      }
    });
    defined_passes.put("sat_check",new CompilerPass("insert satisfyability checks for all methods"){
      public ProgramUnit apply(ProgramUnit arg){
        return new SatCheckRewriter(arg).rewriteAll();
      }
    });
    defined_passes.put("silver_constructors",new CompilerPass("convert constructors to silver style"){
      public ProgramUnit apply(ProgramUnit arg){
        return new SilverConstructors(arg).rewriteAll();
      }
    });
    defined_passes.put("silver-class-reduction",new CompilerPass("reduce classes to single Ref class"){
      public ProgramUnit apply(ProgramUnit arg){
        return new SilverClassReduction(arg).rewriteAll();
      }
    });
    defined_passes.put("silver-reorder",new CompilerPass("move declarations from inside if-then-else blocks to top"){
      public ProgramUnit apply(ProgramUnit arg){
        return new SilverReorder(arg).rewriteAll();
      }
    });
    defined_passes.put("simplify_calls",new CompilerPass("???"){
      public ProgramUnit apply(ProgramUnit arg){
        return new SimplifyCalls(arg).rewriteAll();
      }
    });
    defined_passes.put("simplify_expr",new CompilerPass("Simplify expressions"){
      public ProgramUnit apply(ProgramUnit arg){
        return new SimplifyExpressions(arg).rewriteAll();
      }
    });
    defined_passes.put("standardize",new CompilerPass("Standardize representation"){
      public ProgramUnit apply(ProgramUnit arg){
        return new Standardize(arg).rewriteAll();
      }
    });
    defined_passes.put("strip_constructors",new CompilerPass("Strip constructors from classes"){
      public ProgramUnit apply(ProgramUnit arg){
        return new StripConstructors(arg).rewriteAll();
      }
    });
    defined_checks.put("verifast",new ValidationPass("verify with VeriFast"){
      public TestReport apply(ProgramUnit arg){
        vct.java.printer.JavaPrinter.dump(System.out,JavaDialect.JavaVeriFast,arg);
        return vct.verifast.Main.TestVerifast(arg);
      }
    });
    defined_passes.put("voidcalls",new CompilerPass("???"){
      public ProgramUnit apply(ProgramUnit arg){
        return new VoidCalls(arg).rewriteAll();
      }
    });
    defined_passes.put("chalice-preprocess",new CompilerPass("Pre processing for chalice"){
      public ProgramUnit apply(ProgramUnit arg){
        return new ChalicePreProcess(arg).rewriteAll();
      }
    });
    if (help_passes.get()) {
      System.out.println("The following passes are available:"); 
      for (Entry<String, CompilerPass> entry:defined_passes.entrySet()){
        System.out.printf(" %-12s : %s%n",entry.getKey(),entry.getValue().getDescripion());
      }
      for (Entry<String, ValidationPass> entry:defined_checks.entrySet()){
        System.out.printf(" %-12s : %s%n",entry.getKey(),entry.getValue().getDescripion());
      }
      System.exit(0);
    }
    if (CommandLineTesting.enabled()){
      CommandLineTesting.run_testsuites();
      System.exit(0);
    }
    if (!(boogie.get() || chalice.get() || chalice2sil.get() || silver.used() || dafny.get() || verifast.get() || pass_list.iterator().hasNext())) {
      Fail("no back-end or passes specified");
    }
    Progress("parsing inputs...");
    int cnt = 0;
    long startTime = System.currentTimeMillis();
    for(String name : input){
      File f=new File(name);
      if (!no_context.get()){
        FileOrigin.add(name);
      }
      parseFile(f.getPath());
      cnt++;
    }
    Progress("Parsed %d files in: %dms",cnt,System.currentTimeMillis() - startTime);
    if (boogie.get() || sequential_spec.get()) {
      program.setSpecificationFormat(SpecificationFormat.Sequential);
    }
    /*
    startTime = System.currentTimeMillis();
    program=new Standardize(program).rewriteAll();
    new SimpleTypeCheck(program).check();
    Progress("Initial type check took %dms",System.currentTimeMillis() - startTime);
    */
    FeatureScanner features=new FeatureScanner();
    program.accept(features);
    classes=new ArrayList();
    for (ClassName name:program.classNames()){
      classes.add(name);
    }
    List<String> passes=null;
    if (boogie.get()) {
    	passes=new ArrayList<String>();
      passes.add("standardize");
      passes.add("check");
      passes.add("flatten");
      passes.add("assign");
      passes.add("finalize_args");
      passes.add("reorder");
      passes.add("simplify_calls");
      if (infer_modifies.get()) {
        passes.add("standardize");
        passes.add("check");
        passes.add("modifies");
      }
      passes.add("standardize");
      passes.add("check");
      passes.add("voidcalls");
      passes.add("standardize");
      passes.add("check");
      passes.add("flatten");
      passes.add("reorder");
      passes.add("standardize");
      passes.add("check");
      passes.add("strip_constructors");
      passes.add("standardize");
      passes.add("check");
    	passes.add("boogie");
    } else if (chalice.get()||chalice2sil.get()) {
      passes=new ArrayList<String>();
      if (sat_check.get()) passes.add("sat_check");
      passes.add("standardize");
      passes.add("check");        
      passes.add("magicwand");
      passes.add("standardize");
      passes.add("check");
      if (inline_predicates.get()){
        passes.add("inline");
        passes.add("standardize");
        passes.add("check");        
      }
      if (features.hasStaticItems()){
        Warning("Encoding globals by means of an argument.");
        passes.add("standardize");
        passes.add("check");
        passes.add("globalize");
        passes.add("standardize");
        passes.add("check");
      }
      if (features.usesIterationContracts()){
        passes.add("iter");
        passes.add("standardize");
        passes.add("check");
      }
      if (features.usesKernels()){
        passes.add("kernel-split");
        passes.add("simplify_expr");
        passes.add("standardize");
        passes.add("check");       
      }
      if (features.usesInheritance()){
        passes.add("standardize");
        passes.add("check");       
        passes.add("ds_inherit");
        passes.add("standardize");
        passes.add("check");       
      }
      if (check_defined.get()){
        passes.add("check-defined");
        passes.add("standardize");
        passes.add("check");
      }
      if (check_csl.get()){
        passes.add("csl-encode");
        passes.add("standardize");
        passes.add("check");
      }
      if (explicit_encoding.get()){
        //passes.add("standardize");
        //passes.add("check");       
        passes.add("explicit_encoding");
        passes.add("standardize");
        passes.add("check");
      } else {
        passes.add("flatten_before_after");
        passes.add("standardize");
        passes.add("check");        
      }
      passes.add("recognize_arrays");
      passes.add("standardize");
      passes.add("check");
      passes.add("rewrite_arrays");
      passes.add("standardize");
      passes.add("check");
      passes.add("flatten");
      passes.add("finalize_args");
      passes.add("reorder");
      passes.add("standardize");
      passes.add("check");
      if (features.usesDoubles()){
        Warning("defining Double");
        passes.add("define_double");
        passes.add("standardize");
        passes.add("check");
      }
    	passes.add("assign");
      passes.add("reorder");
    	passes.add("standardize");
    	passes.add("check");
      passes.add("rm_cons");
      passes.add("standardize");
      passes.add("check");
      passes.add("voidcalls");
      passes.add("standardize");
      passes.add("check");
      passes.add("flatten");
      passes.add("reorder");
      passes.add("check");
      passes.add("chalice-preprocess");
      passes.add("check");
      if (chalice.get()){
        passes.add("chalice");
      } else {
        passes.add("silicon-chalice");
      }
    } else if (dafny.get()) {
      passes=new ArrayList<String>();
      passes.add("standardize");
      passes.add("check");
      passes.add("voidcalls");
      passes.add("standardize");
      passes.add("check");
      //passes.add("flatten");
      //passes.add("reorder");
      //passes.add("check");
      passes.add("dafny");
    } else if (verifast.get()) {
      passes=new ArrayList<String>();
      passes.add("standardize");
      passes.add("check");
      passes.add("verifast");
    } else if (silver.used()) {
      passes=new ArrayList<String>();
      passes.add("standardize");
      passes.add("check");
      passes.add("standardize");
      passes.add("check");
      if (features.usesIterationContracts()){
        passes.add("iter");
        passes.add("standardize");
        passes.add("check");
      }
      if (features.usesKernels()){
        passes.add("kernel-split");
        passes.add("simplify_expr");
        passes.add("standardize");
        passes.add("check");       
      }
      if (check_defined.get()){
        passes.add("check-defined");
        passes.add("standardize");
        passes.add("check");
      }
      if (check_csl.get()){
        passes.add("csl-encode");
        passes.add("standardize");
        passes.add("check");
      }
      if (check_history.get()){
        passes.add("check-history");
        passes.add("standardize");
        passes.add("check");
      }
      passes.add("flatten");
      passes.add("assign");
      passes.add("reorder");
      passes.add("standardize");
      passes.add("check");
      // Split into class-conversion + silver-class-reduction
      // TODO: check if no other functionality destroyed.
      //passes.add("silver_constructors");
      //passes.add("standardize");
      //passes.add("check");
      if (!check_csl.get()){
        passes.add("ref_array");
        passes.add("standardize");
        passes.add("check");
      }
      passes.add("class-conversion");
      passes.add("standardize");
      passes.add("check");
      passes.add("silver-class-reduction");
      passes.add("standardize");
      passes.add("check");
      passes.add("voidcalls");
      passes.add("standardize");
      passes.add("check");
      passes.add("ghost-lift");
      passes.add("standardize");
      passes.add("check");
      passes.add("flatten");
      passes.add("reorder");
      passes.add("silver-reorder");
      passes.add("abstract-resolve");
      passes.add("standardize");
      passes.add("check");
      passes.add("silver");     
    } else {
    	if (!pass_list.iterator().hasNext()) Abort("no back-end or passes specified");
    }
    {
      TestReport res=null;
      for(String pass:passes!=null?passes:pass_list){
        if (res!=null){
          Progress("Ignoring intermediate verdict %s",res.getVerdict());
          res=null;
        }
        CompilerPass task=defined_passes.get(pass);
        if (show_before.contains(pass)){
          String name=show_file.get();
          if (name!=null){
            String file=String.format(name, pass);
            PrintStream out=new PrintStream(new FileOutputStream(file));
            vct.util.Configuration.getDiagSyntax().print(out,program);
            out.close();
          } else {
            vct.util.Configuration.getDiagSyntax().print(System.out,program);
          }
        }
        if (task!=null){
          Progress("Applying %s ...",pass);
          startTime = System.currentTimeMillis();
          program=task.apply(program);
          Progress(" ... pass took %d ms",System.currentTimeMillis()-startTime);
        } else {
          ValidationPass check=defined_checks.get(pass);
          if (check!=null){
            Progress("Applying %s ...", pass);
            startTime = System.currentTimeMillis();
            res=check.apply(program);
            Progress(" ... pass took %d ms",System.currentTimeMillis()-startTime);
          } else {
            Fail("unknown pass %s",pass);
          }
        }
        if (show_after.contains(pass)){
          String name=show_file.get();
          if (name!=null){
            String file=String.format(name, pass);
            PrintStream out=new PrintStream(new FileOutputStream(file));
            vct.util.Configuration.getDiagSyntax().print(out,program);
            out.close();
          } else {
            vct.util.Configuration.getDiagSyntax().print(System.out,program);
          }
        }
        if (stop_after.contains(pass)){
          Fail("exit after pass %s",pass);
        }
      }
      if (res!=null) {
        Output("The final verdict is %s",res.getVerdict());
      } else {
        Fail("No overall verdict has been set. Check the output carefully!");
      }
    }
    Output("entire run took %d ms",System.currentTimeMillis()-globalStart);
  }
}

