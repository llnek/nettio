/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright © 2013-2022, Kenneth Leung. All rights reserved. */

package czlab.nettio;

import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.MixedAttribute;
import io.netty.handler.codec.http.multipart.MixedFileUpload;
import java.nio.charset.Charset;


/**
 */
public class H1DataFactory extends DefaultHttpDataFactory {

  private static boolean isEmpty(String n) {
    return (n==null || n.length() ==0);
  }

  /**
   */
  public H1DataFactory(long minSize) {
    super(minSize);
  }

  @Override
  public Attribute createAttribute(HttpRequest req, String name) {
    Attribute a= super.createAttribute(req, isEmpty(name) ? FNAME : name);
    assert(a instanceof MixedAttribute);
    return a;
  }

  @Override
  public Attribute createAttribute(HttpRequest req, String name, String value) {
    Attribute a= super.createAttribute(req, isEmpty(name) ? FNAME : name, value);
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
        isEmpty(name) ? FNAME : name,
        filename, contentType, contentTransferEncoding, charset, size);
    assert(u instanceof MixedFileUpload);
    return u;
  }

  private static final String FNAME= "_blank_field_";

}


