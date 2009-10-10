// $Id$
package BFT;

public class Debug{

   public static boolean debug = true;
    public static boolean profile = false;


    public static boolean COMMIT = true;
    public static boolean VIEWCHANGE = true;
    public static boolean STATUS = true;
    public static boolean QUEUE = true;
    public static boolean WAITS = true;

    public static void println(Object obj) {
        if(debug) {
            System.out.println(obj);
        }
    }
    
    public static void println(String str) {
        if(debug) {
            System.out.println(str);
        }
    }
    
    public static void println(boolean cond, Object st){
	if (debug && cond)
	   System.out.println(st);
    }

    public static void println() {
        if(debug) {
            //System.out.println();
        }
    }
    
    public static void print(Object obj) {
        if(debug) {
            System.out.print(obj);
        }
    }

    static public void kill(Exception e){
	e.printStackTrace();
	System.exit(0);
    }

    public static void kill(String st){
	kill(new RuntimeException(st));
    }


    static protected long baseline = 0;//System.currentTimeMillis() - 1000000;
    public static void profileStart(String s){
	if (!profile) return;
	String tmp = Thread.currentThread() +" "+ s + " START "+(System.currentTimeMillis()-baseline);
	System.out.println(tmp);
    }

    public static void profileFinis(String s){
	if (!profile) return;
	String tmp = Thread.currentThread() +" "+ s + " FINIS "+(System.currentTimeMillis() - baseline);
	System.out.println(tmp);
    }
    
    public static void printQueue(String s) {
    	if(QUEUE) {
    		System.out.println(s);
    	}
    }

    public static void printWaits(String s){
	if (WAITS) System.out.println(s);
    }

}
