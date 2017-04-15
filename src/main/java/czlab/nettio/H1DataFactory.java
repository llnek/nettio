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

import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.MixedAttribute;
import io.netty.handler.codec.http.multipart.MixedFileUpload;
import java.nio.charset.Charset;
import czlab.jasal.CU;


/**
 * @author Kenneth Leung
 */
public class H1DataFactory extends DefaultHttpDataFactory {

  /**
   */
  public H1DataFactory(long minSize) {
    super(minSize);
  }

  @Override
  public Attribute createAttribute(HttpRequest req, String name) {
    Attribute a= super.createAttribute(req, CU.isEmpty(name) ? FNAME : name);
    assert(a instanceof MixedAttribute);
    return a;
  }

  @Override
  public Attribute createAttribute(HttpRequest req, String name, String value) {
    Attribute a= super.createAttribute(req, CU.isEmpty(name) ? FNAME : name, value);
    assert(a instanceof MixedAttribute);
    return a;
  }

  @Override
  public FileUpload createFileUpload(HttpRequest req,
                          String name,
                          String filename,
                          String contentType,
                          String contentTransferEncoding,
                          Charset charset,
                          long size) {
    FileUpload u= super.createFileUpload(req,
        CU.isEmpty(name) ? FNAME : name,
        filename, contentType, contentTransferEncoding, charset, size);
    assert(u instanceof MixedFileUpload);
    return u;
  }

  private static final String FNAME= "_blank_field_";

}


