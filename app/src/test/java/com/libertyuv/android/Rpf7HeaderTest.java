package com.libertyuv.android;
import org.junit.Test;
import static org.junit.Assert.*;
public class Rpf7HeaderTest {
 @Test public void parsesXboxHeader() throws Exception {
  byte[] h={'R','P','F','7',0,0,0,1,(byte)0x90,0,0,16,0,0,0,0};
  Rpf7Archive.Header p=Rpf7Archive.parseHeader(h);
  assertEquals(1,p.entriesCount); assertEquals(1,p.platformBit); assertEquals(1,p.nameShift); assertEquals(16,p.namesLength);
 }
 @Test(expected=java.io.IOException.class) public void rejectsWrongMagic() throws Exception { Rpf7Archive.parseHeader(new byte[16]); }
 @Test(expected=java.io.EOFException.class) public void rejectsShortHeader() throws Exception { Rpf7Archive.parseHeader(new byte[8]); }
}
