/*
   Copyright (c) 2018 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.linkedin.data.codec.entitystream;

import com.linkedin.data.DataComplex;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.async.NonBlockingJsonParser;


/**
 * A JSON decoder for a {@link DataComplex} object implemented as a {@link com.linkedin.entitystream.Reader} reading
 * from an {@link com.linkedin.entitystream.EntityStream} of ByteString. The implementation is backed by Jackson's
 * {@link NonBlockingJsonParser}. Because the raw bytes are pushed to the decoder, it keeps the partially built data
 * structure in a stack.
 */
public class JacksonJsonDataDecoder<T extends DataComplex> extends AbstractJacksonDataDecoder<T> implements JsonDataDecoder<T>
{
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  protected JacksonJsonDataDecoder(byte expectedFirstToken)
  {
    super(JSON_FACTORY, expectedFirstToken);
  }

  public JacksonJsonDataDecoder()
  {
    super(JSON_FACTORY);
  }
}
