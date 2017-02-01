/**
 * Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package czlab.convoy.nettio;

import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.stream.ChunkedInput;


/**
 * @author Kenneth Leung
 */
public class HttpRanges implements ChunkedInput<ByteBuf> {

  public static final String DEF_BD= "21458390-ebd6-11e4-b80c-0800200c9a66";
  public static final Logger TLOG= getLogger(HttpRanges.class);

  private List<ByteRangeChunk> _ranges;
  private Object _source;

  private boolean _bad = false;
  private String _cType;

  private long _totalBytes=0L;
  private long _bytesRead=0L;
  private long _flen= 0L;
  private int _current= 0;

  /**
   */
  private static boolean isValid(String rangeStr) {
    return rangeStr != null &&
      rangeStr.length() > 0 &&
      rangeStr.matches("^\\s*bytes=[0-9,-]+");
  }

  /**/
  public static HttpRanges eval(String range,
                                String cType,
                                Object source) throws IOException {
    HttpRanges rc= null;
    if (isValid(range)) {
      rc= new HttpRanges(cType, source);
      if (! rc.init(range)) {
        rc=null;
      }
    }
    return rc;
  }

  /**/
  public static HttpRanges eval(String range,
                                Object source) throws IOException {
    return eval(range, "application/octet-stream", source);
  }

  /**
   */
  protected HttpRanges(String cType, Object source) throws IOException {
    if (source instanceof RandomAccessFile) {
      RandomAccessFile f= (RandomAccessFile) source;
      _flen= f.length();
      _source =f;
    }
    else
    if (source instanceof File) {
      RandomAccessFile f= new RandomAccessFile((File) source, "r");
      _flen= f.length();
      _source =f;
    }
    else
    if (source instanceof byte[]) {
      _flen= ((byte[]) source).length;
      _source=source;
    }
    else {
      throw new IllegalArgumentException();
    }

    _cType= cType;
  }

  /**
   */
  public static void fmtError(HttpResponse rsp, long totalSize) {
    rsp.setStatus(HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    rsp.headers().add(
        HttpHeaderNames.ACCEPT_RANGES,
        HttpHeaderValues.BYTES);
    long last= totalSize > 0 ? totalSize-1 : 0;
    if (totalSize < 0) totalSize=0;
    rsp.headers().set(HttpHeaderNames.CONTENT_RANGE,
                      "bytes 0-" +
                      Long.toString(last) +
                      "/" +
                      Long.toString(totalSize));
    rsp.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
  }

  /**
   */
  public void fmtResponse(HttpResponse rsp) {

    rsp.setStatus(HttpResponseStatus.PARTIAL_CONTENT);
    rsp.headers().add(
        HttpHeaderNames.ACCEPT_RANGES,
        HttpHeaderValues.BYTES);

    if (_ranges.size() == 1) {
      ByteRangeChunk r= _ranges.get(0);
      rsp.headers().set(HttpHeaderNames.CONTENT_RANGE,
                        HttpHeaderValues.BYTES +
                        " " +
                        Long.toString(r.start()) +
                        "-" +
                        Long.toString(r.end()) +
                        "/" +
                        Long.toString(_flen));
    } else {
      rsp.headers().set(HttpHeaderNames.CONTENT_TYPE,
                        "multipart/byteranges; boundary="+ DEF_BD);
    }

    rsp.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                      Long.toString(_totalBytes));
  }

  /**
   */
  @Deprecated
  @Override
  public ByteBuf readChunk(ChannelHandlerContext ctx) throws Exception {
    return readChunk(ctx.alloc());
  }

  @Override
  public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
    byte[] buff= new byte[8192];
    int mlen= buff.length;
    int count = 0;

    while (count < mlen &&
           _current < _ranges.size() &&
           _ranges.get(_current) != null) {
      if (_ranges.get(_current).readableBytes() > 0) {
        count += _ranges.get(_current).pack(buff, count);
      } else {
        _current += 1;
      }
    }

    if (count == 0) {
      return null;
    } else {
      _bytesRead += count;
      return Unpooled.wrappedBuffer(buff);
    }
  }

  @Override
  public long length() { return _totalBytes;  }

  @Override
  public long progress() {
    return _bytesRead;
  }

  /**
   */
  private boolean hasNext() {
    return _current < _ranges.size() &&
           _ranges.get(_current).readableBytes() > 0;
  }

  /**
   */
  public boolean isEndOfInput() { return !hasNext(); }

  @Override
  public void close() {
    if (_source instanceof Closeable)
    try {
      ((Closeable)_source).close();
    } catch (IOException e) {
      TLOG.warn("",e);
    }
    _source=null;
  }

  @Override
  public void finalize() throws Exception {
    close();
  }

  /**
   */
  protected boolean init(String s /* range */) {
    try {
      String[] rvs= s.replaceFirst("^\\s*bytes=", "").trim().split(",");
      List<NumRange> chunks= new ArrayList<>();
      long last= _flen;
      --last;

      if (rvs != null) for (int n=0; n < rvs.length; ++n) {
        String rs= rvs[n].trim();
        long start=0L;
        long end=0L;
        if (rs.startsWith("-")) {
          start = last - Long.valueOf(rs.substring(1).trim());
          end = last;
        } else {
          String[] range = rs.split("-");
          start = Long.valueOf(range[0].trim());
          end = (range.length > 1)
            ? Long.valueOf(range[1].trim()) : last;
        }
        if (end > last) { end = last; }
        if (start <= end) {
          chunks.add(new NumRange(start, end )); }
      }

      _ranges.clear();

      if (!chunks.isEmpty()) {
        List<NumRange> cs= sanitize(chunks);
        boolean many = cs.size() > 1;
        ByteRangeChunk b;
        for (NumRange r : chunks) {
          if (many) {
            b= new MultiByteRange(_source,_cType,r.start,r.end);
          } else {
            b= new ByteRangeChunk(_source,_cType,r.start,r.end);
          }
          _ranges.add(b);
        }
      }

      if (!_ranges.isEmpty()) {
        _totalBytes= calcTotal();
      } else {
        _bad=true;
      }

    } catch (Throwable e) {
      _bad = true;
      TLOG.error("", e);
    }

    return !_bad;
  }

  /**
   */
  private boolean maybeIntersect(NumRange r1, NumRange r2) {
    return (r1.start >= r2.start && r1.start <= r2.end) ||
           (r1.end >= r2.start && r1.start <= r2.end);
  }

  /**
   */
  private NumRange merge(NumRange r1, NumRange r2) {
    return new NumRange(
      r1.start < r2.start ?  r1.start : r2.start,
      r1.end > r2.end ? r1.end : r2.end
    );
  }


  /**/
  private long calcTotal() {
    long len=0L;
    for (ByteRangeChunk r : _ranges) {
      len += r.calcSize(); }
    return len;
  }

  /**
   */
  private List<NumRange> sanitize(List<NumRange> chunks) {

    NumRange[] sorted= chunks.toArray(new NumRange[0]);
    List<NumRange> rc= chunks;

    Arrays.sort(sorted, new Comparator<NumRange>() {
      public int compare(NumRange t1, NumRange t2) {
        return Long.valueOf(t1.start).compareTo(t2.start);
      }
    });

    rc.clear();
    rc.add(sorted[0]);
    for (int n = 1; n < sorted.length; ++n) {
      NumRange r1 = rc.get(rc.size() - 1);
      NumRange c1 = sorted[n];
      if (maybeIntersect(c1, r1)) {
        rc.set(rc.size() - 1, merge(c1, r1));
      } else {
        rc.add(c1);
      }
    }

    return rc;
  }

}

/**
 */
class NumRange {
  public NumRange(long s, long e) { start=s; end=e; }
  public NumRange() { start=0L; end=0L; }
  public long start;
  public long end;
}

