public class MultiIncrement {

  public static int shared;

  public int owned;
  
  /*@
    requires Perm(owned,100);
    ensures  Perm(owned,100)**owned==\old(owned)+1;
  @*/
  public void incr_this_one(){
    owned=owned+1;
  }

  /*@
    requires Perm(shared,100);
    ensures  Perm(shared,100)**shared==\old(shared)+1;
  @*/
  public void incr_shared_one(){
    shared=shared+1;
  } 

  /*@
    requires Perm(obj.owned,100);
    ensures  Perm(obj.owned,100)**obj.owned==\old(obj.owned)+1;
  @*/
  public static void static_incr_this_one(MultiIncrement obj){
    obj.owned=obj.owned+1;
  }

  /*@
    requires Perm(shared,100);
    ensures  Perm(shared,100)**shared==\old(shared)+1;
  @*/
  public static void static_incr_shared_one(){
    shared=shared+1;
  } 


  /*@
    requires Perm(owned,100)**N>=0;
    ensures  Perm(owned,100)**owned==\old(owned)+N;
  @*/
  public void incr_this_many(int N){
    int i=N;
    //@ loop_invariant Perm(owned,100) ** owned+i==\old(owned)+N ** i>=0;
    while(i>0){
        owned=owned+1;
        i=i-1;
    }
  }

  /*@
    requires Perm(shared,100)**N>=0;
    ensures  Perm(shared,100)**shared==\old(shared)+N;
  @*/
  public void incr_shared_many(int N){
    int i=N;
    //@ loop_invariant Perm(shared,100) ** shared+i==\old(shared)+N ** i>=0;
    while(i>0){
        shared=shared+1;
        i=i-1;
    }
  } 

  /*@
    requires Perm(obj.owned,100)**N>=0;
    ensures  Perm(obj.owned,100)**obj.owned==\old(obj.owned)+N;
  @*/
  public static void static_incr_this_many(MultiIncrement obj,int N){
    int i=N;
    //@ loop_invariant Perm(obj.owned,100) ** obj.owned+i==\old(obj.owned)+N ** i>=0;
    while(i>0){
        obj.owned=obj.owned+1;
        i=i-1;
    }
  }

  /*@
    requires Perm(shared,100)**N>=0;
    ensures  Perm(shared,100)**shared==\old(shared)+N;
  @*/
  public static void static_incr_shared_many(int N){
    int i=N;
    //@ loop_invariant Perm(shared,100) ** shared+i==\old(shared)+N ** i>=0;
    while(i>0){
        shared=shared+1;
        i=i-1;
    }
  } 


}

