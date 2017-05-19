/**
 * Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package czlab.nettio;

import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * @author Kenneth Leung
 */
class ByteRangeChunk {

  public static final Logger TLOG= getLogger(ByteRangeChunk.class);

  protected byte[] _preamble= new byte[0];
  protected Object _source;
  protected long _length;
  protected int _preamblePos= 0;
  protected int _rangePos= 0;
  protected long _start;
  protected long _end;
  protected String _cType;

  /**
   * @throws IOException
   */
  public ByteRangeChunk(Object source,
                        String cType,
                        long start, long end) throws IOException {
    _start= start;
    _end= end;
    _cType= cType;
    init(source);
  }

  /**
   */
  public long size() { return _end - _start + 1; }

  /**
   */
  public long start() { return _start; }

  /**
   */
  public long end() { return _end; }

  /**
   */
  public long calcSize() { return size() + _preamble.length; }

  /**
   */
  public long readableBytes() { return size() - _rangePos; }

  /**
   */
  public int pack(byte[] out, int offset) throws IOException {
    int bufLen= out.length;
    int pos=offset;
    int count= 0;
    while (pos < bufLen &&
           _preamblePos < _preamble.length) {
      out[pos] = _preamble[_preamblePos];
      _preamblePos += 1;
      pos += 1;
      count += 1;
    }
    if (pos < bufLen) {
      long r = readableBytes();
      long d = bufLen-pos;
      long len = (r > d) ? d : r;
      if (len > Integer.MAX_VALUE) {
        len = Integer.MAX_VALUE;
      }
      int c = read(out, pos, (int)len);
      if (c < 0) {
        throw new IOException(
            "error while reading file : " +
            "length=" + _length + ", " +
            "seek=" + Long.toString(_start + _rangePos));
      }
      _rangePos += c;
      count += c;
    }

    return count;
  }

  protected long length() { return _length; }

  /**
   * @throws IOException
   */
  private int read(byte[] out, int pos, int len) throws IOException {
    long target= _start + _rangePos;
    int c= -1;
    if (_source instanceof RandomAccessFile) {
      RandomAccessFile f= (RandomAccessFile) _source;
      f.seek(target);
      c = f.read(out, pos, len);
    }
    if (_source instanceof ByteArrayInputStream) {
      ByteArrayInputStream inp= (ByteArrayInputStream) _source;
      inp.reset();
      inp.skip(target);
      c= inp.read(out, pos, len);
    }
    return c;
  }

  /**
   * @throws IOException
   */
  private void init(Object source) throws IOException {
    long len=0L;

    if (source instanceof File) {
      RandomAccessFile f= new RandomAccessFile((File) source, "r");
      len= f.length();
      _source=f;
    }
    else
    if (source instanceof RandomAccessFile) {
      RandomAccessFile f= (RandomAccessFile) source;
      len= f.length();
      _source=f;
    }
    else
    if (source instanceof byte[]) {
      byte[] b= (byte[]) source;
      ByteArrayInputStream inp= new ByteArrayInputStream(b);
      len= b.length;
      inp.mark(0);
      _source=inp;
    }
    else {
      throw new IllegalArgumentException();
    }

    _length= len;
  }

}

/**
 */
class MultiByteRange extends ByteRangeChunk {

  /**
   */
  public MultiByteRange(Object source,
                        String cType,
                        long start,
                        long end) throws IOException {
    super(source, cType, start, end);
    _preamble=
    new StringBuilder("--")
      .append(HttpRanges.DEF_BD)
      .append("\r\n")
      .append("Content-Type: ")
      .append(_cType)
      .append("\r\n")
      .append("Content-Range: bytes ")
      .append(Long.toString(_start))
      .append("-")
      .append(Long.toString(_end))
      .append("/")
      .append(Long.toString(length()))
      .append("\r\n\r\n")
      .toString()
      .getBytes("utf-8");
  }

}






