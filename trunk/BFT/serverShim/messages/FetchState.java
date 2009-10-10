// $Id$

package BFT.serverShim.messages;

import BFT.util.UnsignedTypes;
import BFT.messages.MacArrayMessage;

import BFT.Parameters;

public class FetchState extends MacArrayMessage{
    public FetchState( byte[] tok, long sender){
	super(MessageTags.FetchState, 
	      computeSize(tok), sender,
	      Parameters.getExecutionCount());

	token = tok;

	// now lets get the bytes
	byte[] bytes = getBytes();

	// copy the size of the token
	byte[] tmp = UnsignedTypes.intToBytes(token.length);
	int offset = getOffset();
	for (int i = 0; i < tmp.length; i++, offset++)
	    bytes[offset] = tmp[i];
	// copy the token
	for (int i = 0; i < token.length; i++, offset++)
	    bytes[offset] = token[i];
    }


    public FetchState(byte[] bytes){
	super(bytes);
	if (getTag() != MessageTags.FetchState)
	    throw new RuntimeException("invalid message Tag: "+getTag());
		
	// pull the length of the token
	byte[] tmp = new byte[BFT.messages.MessageTags.uint16Size];
	int offset = getOffset();
	for (int i = 0; i < tmp.length; i++, offset++)
	    tmp[i] = bytes[offset];
	int size = UnsignedTypes.bytesToInt(tmp);
	token = new byte[size];
	// pull the token itself
	for (int i = 0; i < token.length; i++, offset++)
	    token[i] = bytes[offset];

	if (offset != bytes.length - getAuthenticationSize())
	    throw new RuntimeException("Invalid byte input");
    }


    protected  byte[] token;

    public long getSendingReplica(){
	return getSender();
    }

    public byte[] getToken(){
	return token;
    }

    private static int computeSize(byte[] tok){
	return  MessageTags.uint16Size + tok.length;
    }

    
    public String toString(){
	return "<FETCH-STATE, "+super.toString()+", token: "+token+">";
    }

    public static void main(String args[]){
	byte[] tmp = new byte[8];
	for (int i = 0; i < 8; i++)
	    tmp[i] = (byte)i;
	FetchState vmb = 
	    new FetchState( tmp, 1);
	//System.out.println("initial: "+vmb.toString());
	UnsignedTypes.printBytes(vmb.getBytes());
	FetchState vmb2 = 
	    new FetchState(vmb.getBytes());
	//System.out.println("\nsecondary: "+vmb2.toString());
	UnsignedTypes.printBytes(vmb2.getBytes());

	for (int i = 0; i < 8; i++)
	    tmp[i] = (byte) (tmp[i] * tmp[i]);

	vmb = new FetchState( tmp, 134);
	//System.out.println("initial: "+vmb.toString());
	UnsignedTypes.printBytes(vmb.getBytes());
	 vmb2 = new FetchState(vmb.getBytes());
	//System.out.println("\nsecondary: "+vmb2.toString());
	UnsignedTypes.printBytes(vmb2.getBytes());
 
	//System.out.println("old = new: "+(vmb2.toString().equals(vmb.toString())));

   }

}