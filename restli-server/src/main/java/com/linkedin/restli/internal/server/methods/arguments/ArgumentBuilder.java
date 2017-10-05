/*
   Copyright (c) 2012 LinkedIn Corp.

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

/**
 * $Id: $
 */

package com.linkedin.restli.internal.server.methods.arguments;


import com.linkedin.data.DataList;
import com.linkedin.data.DataMap;
import com.linkedin.data.schema.ArrayDataSchema;
import com.linkedin.data.schema.DataSchemaUtil;
import com.linkedin.data.schema.validation.CoercionMode;
import com.linkedin.data.schema.validation.RequiredMode;
import com.linkedin.data.schema.validation.ValidateDataAgainstSchema;
import com.linkedin.data.schema.validation.ValidationOptions;
import com.linkedin.data.schema.validation.ValidationResult;
import com.linkedin.data.template.AbstractArrayTemplate;
import com.linkedin.data.template.DataTemplate;
import com.linkedin.data.template.DataTemplateUtil;
import com.linkedin.data.template.DynamicRecordTemplate;
import com.linkedin.data.template.InvalidAlternativeKeyException;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.data.template.TemplateRuntimeException;
import com.linkedin.internal.common.util.CollectionUtils;
import com.linkedin.r2.message.rest.RestMessage;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.restli.common.BatchRequest;
import com.linkedin.restli.common.ComplexResourceKey;
import com.linkedin.restli.common.CompoundKey;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.common.ProtocolVersion;
import com.linkedin.restli.common.RestConstants;
import com.linkedin.restli.common.TypeSpec;
import com.linkedin.restli.internal.common.PathSegment;
import com.linkedin.restli.internal.common.QueryParamsDataMap;
import com.linkedin.restli.internal.server.RoutingResult;
import com.linkedin.restli.internal.server.ServerResourceContext;
import com.linkedin.restli.internal.server.model.Parameter;
import com.linkedin.restli.internal.server.model.ResourceMethodDescriptor;
import com.linkedin.restli.internal.server.model.ResourceModel;
import com.linkedin.restli.internal.server.util.AlternativeKeyCoercerException;
import com.linkedin.restli.internal.server.util.ArgumentUtils;
import com.linkedin.restli.internal.server.util.DataMapUtils;
import com.linkedin.restli.internal.server.util.RestUtils;
import com.linkedin.restli.common.validation.RestLiDataValidator;
import com.linkedin.restli.server.UnstructuredDataWriter;
import com.linkedin.restli.server.Key;
import com.linkedin.restli.server.PagingContext;
import com.linkedin.restli.server.ResourceConfigException;
import com.linkedin.restli.server.ResourceContext;
import com.linkedin.restli.server.RestLiServiceException;
import com.linkedin.restli.server.RoutingException;
import com.linkedin.restli.server.annotations.HeaderParam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * @author Josh Walker
 * @version $Revision: $
 */
public class ArgumentBuilder
{
  /**
   * Build arguments for resource method invocation. Combines various types of arguments
   * into a single array.
   *
   * @param positionalArguments pass-through arguments coming from
   *          {@link RestLiArgumentBuilder}
   * @param resourceMethod the resource method
   * @param context {@link ResourceContext}
   * @param template {@link DynamicRecordTemplate}
   * @return array of method argument for method invocation.
   */
  @SuppressWarnings("deprecation")
  public static Object[] buildArgs(final Object[] positionalArguments,
                                   final ResourceMethodDescriptor resourceMethod,
                                   final ResourceContext context,
                                   final DynamicRecordTemplate template)
  {
    List<Parameter<?>> parameters = resourceMethod.getParameters();
    Object[] arguments = Arrays.copyOf(positionalArguments, parameters.size());

    fixUpComplexKeySingletonArraysInArguments(arguments);

    boolean attachmentsDesired = false;
    for (int i = positionalArguments.length; i < parameters.size(); ++i)
    {
      Parameter<?> param = parameters.get(i);
      try
      {
        if (param.getParamType() == Parameter.ParamType.KEY || param.getParamType() == Parameter.ParamType.ASSOC_KEY_PARAM)
        {
          Object value = context.getPathKeys().get(param.getName());
          if (value != null)
          {
            arguments[i] = value;
            continue;
          }
        }
        else if (param.getParamType() == Parameter.ParamType.CALLBACK)
        {
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.PARSEQ_CONTEXT_PARAM || param.getParamType() == Parameter.ParamType.PARSEQ_CONTEXT)
        {
          continue; // don't know what to fill in yet
        }
        else if (param.getParamType() == Parameter.ParamType.HEADER)
        {
          HeaderParam headerParam = param.getAnnotations().get(HeaderParam.class);
          String value = context.getRequestHeaders().get(headerParam.value());
          arguments[i] = value;
          continue;
        }
        //Since we have multiple different types of MaskTrees that can be passed into resource methods,
        //we must evaluate based on the param type (annotation used)
        else if (param.getParamType() == Parameter.ParamType.PROJECTION || param.getParamType() == Parameter.ParamType.PROJECTION_PARAM)
        {
          arguments[i] = context.getProjectionMask();
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.METADATA_PROJECTION_PARAM)
        {
          arguments[i] = context.getMetadataProjectionMask();
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.PAGING_PROJECTION_PARAM)
        {
          arguments[i] = context.getPagingProjectionMask();
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.CONTEXT || param.getParamType() == Parameter.ParamType.PAGING_CONTEXT_PARAM)
        {
          PagingContext ctx = RestUtils.getPagingContext(context, (PagingContext) param.getDefaultValue());
          arguments[i] = ctx;
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.PATH_KEYS || param.getParamType() == Parameter.ParamType.PATH_KEYS_PARAM)
        {
          arguments[i] = context.getPathKeys();
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.PATH_KEY_PARAM) {
          Object value = context.getPathKeys().get(param.getName());

          if (value != null)
          {
            arguments[i] = value;
            continue;
          }
        }
        else if (param.getParamType() == Parameter.ParamType.RESOURCE_CONTEXT || param.getParamType() == Parameter.ParamType.RESOURCE_CONTEXT_PARAM)
        {
          arguments[i] = context;
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.VALIDATOR_PARAM)
        {
          RestLiDataValidator validator = new RestLiDataValidator(resourceMethod.getResourceModel().getResourceClass().getAnnotations(),
                                                                  resourceMethod.getResourceModel().getValueClass(), resourceMethod.getMethodType());
          arguments[i] = validator;
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.RESTLI_ATTACHMENTS_PARAM)
        {
          arguments[i] = ((ServerResourceContext)context).getRequestAttachmentReader();
          attachmentsDesired = true;
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.UNSTRUCTURED_DATA_WRITER_PARAM)
        {
          UnstructuredDataWriter unstructuredDataWriter = new UnstructuredDataWriter(new ByteArrayOutputStream(), ((ServerResourceContext)context));
          arguments[i] = unstructuredDataWriter;
          continue;
        }
        else if (param.getParamType() == Parameter.ParamType.POST)
        {
          // handle action parameters
          if (template != null)
          {
            DataMap data = template.data();
            if (data.containsKey(param.getName()))
            {
              arguments[i] = template.getValue(param);
              continue;
            }
          }
        }
        else if (param.getParamType() == Parameter.ParamType.QUERY)
        {
          Object value;
          if (DataTemplate.class.isAssignableFrom(param.getType()))
          {
            value = buildDataTemplateArgument(context, param);
          }
          else
          {
            value = buildRegularArgument(context, param);
          }

          if (value != null)
          {
            arguments[i] = value;
            continue;
          }
        }
        else if (param.getParamType() == Parameter.ParamType.BATCH || param.getParamType() == Parameter.ParamType.RESOURCE_KEY)
        {
          // should not come to this routine since it should be handled by passing in positionalArguments
          throw new RoutingException("Parameter '" + param.getName() + "' should be passed in as a positional argument",
              HttpStatus.S_400_BAD_REQUEST.getCode());
        }
        else
        {
          // unknown param type
          throw new RoutingException(
              "Parameter '" + param.getName() + "' has an unknown parameter type '" + param.getParamType().name() + "'",
              HttpStatus.S_400_BAD_REQUEST.getCode());
        }
      }
      catch (TemplateRuntimeException e)
      {
        throw new RoutingException("Parameter '" + param.getName() + "' is invalid", HttpStatus.S_400_BAD_REQUEST.getCode());
      }

      try
      {
        // Handling null-valued parameters not provided in resource context or entity body
        // check if it is optional parameter
        if (param.isOptional() && param.hasDefaultValue())
        {
          arguments[i] = param.getDefaultValue();
        }
        else if (param.isOptional() && !param.getType().isPrimitive())
        {
          // optional primitive parameter must have default value or provided
          arguments[i] = null;
        }
        else
        {
          throw new RoutingException("Parameter '" + param.getName() + "' is required", HttpStatus.S_400_BAD_REQUEST.getCode());
        }
      }
      catch (ResourceConfigException e)
      {
        // Parameter default value format exception should result in server error code 500.
        throw new RestLiServiceException(HttpStatus.S_500_INTERNAL_SERVER_ERROR,
            "Parameter '" + param.getName() + "' default value is invalid", e);
      }
    }

    //Verify that if the resource method did not expect attachments, and attachments were present, that we drain all
    //incoming attachments and send back a bad request. We must take precaution here since simply ignoring the request
    //attachments is not correct behavior here. Ignoring other request level constructs such as headers or query parameters
    //that were not needed is safe, but not for request attachments.
    if (!attachmentsDesired && ((ServerResourceContext)context).getRequestAttachmentReader() != null)
    {
      throw new RestLiServiceException(HttpStatus.S_400_BAD_REQUEST,
                                       "Resource method endpoint invoked does not accept any request attachments.");
    }
    return arguments;
  }

  /**
   * Because of backwards compatibility concerns, array fields of the key component of a
   * {@link ComplexResourceKey}s in a get request will be represented in the request url in the old
   * style.  That is, if an array field has the name "a", and contains [1,2] the part of the url
   * representing the serialized array will look like  "a=1&a=2".  However, if the array is a
   * singleton it will just be represented by "a=1". Therefore it is not possible to distinguish
   * between a single value itself and an array containing a single value.
   *
   * The purpose of this function is to fix up the singleton array problem by checking to see if the
   * request is a ComplexKey, whether that ComplexKey's key part has an array component, and, if so
   * and the data for that field is NOT a dataList, placing the data into a dataList.
   *
   * @param arguments the final list of all the arguments.
   */
  private static void fixUpComplexKeySingletonArraysInArguments(Object[] arguments)
  {
    for(int i=0; i < arguments.length; i++)
    {
      Object k = arguments[i];
      if (k instanceof ComplexResourceKey)
      {
        ComplexResourceKey<?, ?> complexResourceKey = (ComplexResourceKey<?, ?>)k;
        ComplexResourceKey<?, ?> newKey = QueryParamsDataMap.fixUpComplexKeySingletonArray(complexResourceKey);
        arguments[i] = newKey;
      }
    }
  }

  /**
   * Build a method argument from a request parameter that is an array
   *
   * @param context {@link ResourceContext}
   * @param param {@link Parameter}
   * @return argument value in the correct type
   */
  private static Object buildArrayArgument(final ResourceContext context,
                                           final Parameter<?> param)
  {
    final Object convertedValue;
    if (DataTemplate.class.isAssignableFrom(param.getItemType()))
    {
      final DataList itemsList = (DataList) context.getStructuredParameter(param.getName());
      convertedValue = Array.newInstance(param.getItemType(), itemsList.size());
      int j = 0;
      for (Object paramData: itemsList)
      {
        final DataTemplate<?> itemsElem = DataTemplateUtil.wrap(paramData, param.getItemType().asSubclass(DataTemplate.class));

        ValidateDataAgainstSchema.validate(itemsElem.data(),
                                           itemsElem.schema(),
                                           new ValidationOptions(RequiredMode.CAN_BE_ABSENT_IF_HAS_DEFAULT,
                                                                 CoercionMode.STRING_TO_PRIMITIVE));

        Array.set(convertedValue, j++, itemsElem);
      }
    }
    else
    {
      final List<String> itemStringValues = context.getParameterValues(param.getName());
      ArrayDataSchema parameterSchema = null;
      if (param.getDataSchema() instanceof ArrayDataSchema)
      {
        parameterSchema = (ArrayDataSchema)param.getDataSchema();
      }
      else
      {
        throw new RoutingException("An array schema is expected.",
                                   HttpStatus.S_400_BAD_REQUEST.getCode());
      }

      convertedValue = Array.newInstance(param.getItemType(), itemStringValues.size());
      int j = 0;
      for (String itemStringValue : itemStringValues)
      {
        if (itemStringValue == null)
        {
          throw new RoutingException("Parameter '" + param.getName()
                                         + "' cannot contain null values", HttpStatus.S_400_BAD_REQUEST.getCode());
        }
        try
        {
          Array.set(convertedValue,
                    j++,
                    ArgumentUtils.convertSimpleValue(itemStringValue, parameterSchema.getItems(), param.getItemType()));
        }
        catch (NumberFormatException e)
        {
          Class<?> targetClass = DataSchemaUtil.dataSchemaTypeToPrimitiveDataSchemaClass(parameterSchema.getItems().getDereferencedType());
          // thrown from Integer.valueOf or Long.valueOf
          throw new RoutingException(String.format("Array parameter '%s' value '%s' must be of type '%s'",
                                                   param.getName(),
                                                   itemStringValue,
                                                   targetClass.getName()),
                                     HttpStatus.S_400_BAD_REQUEST.getCode());
        }
        catch (IllegalArgumentException e)
        {
          // thrown from Enum.valueOf
          throw new RoutingException(String.format("Array parameter '%s' value '%s' is invalid",
                                                   param.getName(),
                                                   itemStringValue),
                                     HttpStatus.S_400_BAD_REQUEST.getCode());
        }
        catch (TemplateRuntimeException e)
        {
          // thrown from DataTemplateUtil.coerceOutput
          throw new RoutingException(String.format("Array parameter '%s' value '%s' is invalid. Reason: %s",
                                                   param.getName(),
                                                   itemStringValue, e.getMessage()),
                                     HttpStatus.S_400_BAD_REQUEST.getCode());
        }
      }
    }

    return convertedValue;
  }

  /**
   * Build a method argument from a request parameter that is NOT backed by a schema, i.e.
   * a primitive or an array
   *
   * @param context {@link ResourceContext}
   * @param param {@link Parameter}
   * @return argument value in the correct type
   */
  private static Object buildRegularArgument(final ResourceContext context,
                                             final Parameter<?> param)
  {
    String value =
        ArgumentUtils.argumentAsString(context.getParameter(param.getName()),
                                       param.getName());

    final Object convertedValue;
    if (value == null)
    {
      return null;
    }
    else
    {
      if (param.isArray())
      {
        convertedValue = buildArrayArgument(context, param);
      }
      else
      {
        try
        {
          convertedValue = ArgumentUtils.convertSimpleValue(value, param.getDataSchema(), param.getType());
        }
        catch (NumberFormatException e)
        {
          Class<?> targetClass = DataSchemaUtil.dataSchemaTypeToPrimitiveDataSchemaClass(param.getDataSchema().getDereferencedType());
          // thrown from Integer.valueOf or Long.valueOf
          throw new RoutingException(String.format("Argument parameter '%s' value '%s' must be of type '%s'",
                                                   param.getName(),
                                                   value,
                                                   targetClass.getName()),
                                     HttpStatus.S_400_BAD_REQUEST.getCode());
        }
        catch (IllegalArgumentException e)
        {
          // thrown from Enum.valueOf
          throw new RoutingException(String.format("Argument parameter '%s' value '%s' is invalid",
                                                   param.getName(),
                                                   value),
                                     HttpStatus.S_400_BAD_REQUEST.getCode());
        }
        catch (TemplateRuntimeException e)
        {
          // thrown from DataTemplateUtil.coerceOutput
          throw new RoutingException(String.format("Argument parameter '%s' value '%s' is invalid. Reason: %s",
                                                   param.getName(),
                                                   value, e.getMessage()),
                                     HttpStatus.S_400_BAD_REQUEST.getCode());
        }
      }
    }

    return convertedValue;
  }

  private static DataTemplate<?> buildDataTemplateArgument(final ResourceContext context,
                                                           final Parameter<?> param)
  {
    Object paramValue = context.getStructuredParameter(param.getName());
    DataTemplate<?> paramRecordTemplate;

    if (paramValue == null)
    {
      return null;
    }
    else
    {
      @SuppressWarnings("unchecked")
      final Class<? extends RecordTemplate> paramType = (Class<? extends RecordTemplate>) param.getType();
      /**
       * It is possible for the paramValue provided by ResourceContext to be coerced to the wrong type.
       * If a query param is a single value param for example www.domain.com/resource?foo=1.
       * Then ResourceContext will parse foo as a String with value = 1.
       * However if a query param contains many values for example www.domain.com/resource?foo=1&foo=2&foo=3
       * Then ResourceContext will parse foo as an DataList with value [1,2,3]
       *
       * So this means if the 'final' type of a query param is an Array and the paramValue we received from
       * ResourceContext is not a DataList we will have to wrap the paramValue inside a DataList
       */
      if (AbstractArrayTemplate.class.isAssignableFrom(paramType) && paramValue.getClass() != DataList.class)
      {
        paramRecordTemplate = DataTemplateUtil.wrap(new DataList(Arrays.asList(paramValue)), paramType);
      }
      else
      {
        paramRecordTemplate = DataTemplateUtil.wrap(paramValue, paramType);
      }

      // Validate against the class schema with FixupMode.STRING_TO_PRIMITIVE to parse the
      // strings into the corresponding primitive types.
      try
      {
        ValidationResult result = ValidateDataAgainstSchema.validate(paramRecordTemplate.data(), paramRecordTemplate.schema(),
            new ValidationOptions(RequiredMode.IGNORE, CoercionMode.STRING_TO_PRIMITIVE));
        if (!result.isValid())
        {
          throw new RoutingException(String.format("Argument parameter '%s' value '%s' is invalid", param.getName(),
              paramRecordTemplate.data()), HttpStatus.S_400_BAD_REQUEST.getCode());
        }
      }
      catch (Exception ex)
      {
        // any validation exception should also throw 400 error.
        throw new RoutingException(String.format("Argument parameter '%s' value '%s' is invalid", param.getName(),
            paramRecordTemplate.data()), HttpStatus.S_400_BAD_REQUEST.getCode());
      }
      return paramRecordTemplate;
    }
  }

  /**
   * @param request {@link com.linkedin.r2.message.rest.RestRequest}
   * @param recordClass resource value class
   * @param <V> resource value type which is a subclass of {@link RecordTemplate}
   * @return resource value
   */
  public static <V extends RecordTemplate> V extractEntity(final RestRequest request,
                                                           final Class<V> recordClass)
  {
    try
    {
      return DataMapUtils.read(request, recordClass);
    }
    catch (IOException e)
    {
      throw new RoutingException("Error parsing entity body: " + e.getMessage(),
                                 HttpStatus.S_400_BAD_REQUEST.getCode());
    }
  }


  /**
   * Extracts the entity from the request body of {@link com.linkedin.r2.message.rest.RestMessage}
   *
   * @param message {@link com.linkedin.r2.message.rest.RestMessage}
   * @return {@link DataMap} representing the body of the message.
   * @throws RoutingException with 400_BAD_REQUEST as status if the message cannot be parsed.
   */
  static DataMap extractEntity(final RestMessage message)
  {
    try
    {
      return DataMapUtils.readMapWithExceptions(message);
    }
    catch (IOException e)
    {
      throw new RoutingException("Cannot parse request entity", HttpStatus.S_400_BAD_REQUEST.getCode(), e);
    }
  }

  /**
   * Convert a DataMap representation of a BatchRequest (string->record) into a Java Map
   * appropriate for passing into application code. Note that compound/complex keys are
   * represented as their string encoding in the DataMap. This method will parse the string
   * encoded keys to compare with the passed in keys from query parameters. During mismatch or
   * duplication of keys in the DataMap, an error will be thrown.
   *
   * @param routingResult {@link RoutingResult} instance for the current request
   * @param data The input DataMap to be converted
   * @param valueClass The RecordTemplate type of the values
   * @param ids The parsed batch ids from the request URI
   * @return A map using appropriate key and value classes, or null if ids is null
   */
  static <R extends RecordTemplate> Map<Object, R> buildBatchRequestMap(final RoutingResult routingResult,
                                                                               final DataMap data,
                                                                               final Class<R> valueClass,
                                                                               final Set<?> ids,
                                                                               final ProtocolVersion version)
  {
    if (ids == null)
    {
      return null;
    }

    BatchRequest<R> batchRequest = new BatchRequest<R>(data, new TypeSpec<R>(valueClass));

    Map<Object, R> result =
      new HashMap<Object, R>(CollectionUtils.getMapInitialCapacity(batchRequest.getEntities().size(), 0.75f), 0.75f);
    for (Map.Entry<String, R> entry : batchRequest.getEntities().entrySet())
    {
      Object typedKey = parseEntityStringKey(entry.getKey(), routingResult, version);

      if (result.containsKey(typedKey))
      {
        throw new RoutingException(
            String.format("Duplicate key in batch request body: '%s'", typedKey),
            HttpStatus.S_400_BAD_REQUEST.getCode());
      }

      if (!ids.contains(typedKey))
      {
        throw new RoutingException(
          String.format("Batch request mismatch. Entity key '%s' not found in the query parameter.", typedKey),
          HttpStatus.S_400_BAD_REQUEST.getCode());
      }

      R value = DataTemplateUtil.wrap(entry.getValue().data(), valueClass);
      result.put(typedKey, value);
    }

    if (!ids.equals(result.keySet()))
    {
      throw new RoutingException(
        String.format("Batch request mismatch. URI keys: '%s' Entity keys: '%s'",
                      ids.toString(),
                      result.keySet().toString()),
        HttpStatus.S_400_BAD_REQUEST.getCode());
    }
    return result;
  }

  /**
   * Parses the provided string key value and returns its corresponding typed key instance. This method should only be
   * used to parse keys which appear in the request body.
   *
   * @param stringKey Key string from the entity body
   * @param routingResult {@link RoutingResult} instance for the current request
   * @param version {@link ProtocolVersion} instance of the current request
   * @return An instance of key's corresponding type
   */
  static Object parseEntityStringKey(final String stringKey, final RoutingResult routingResult,
      final ProtocolVersion version)
  {
    ResourceModel resourceModel = routingResult.getResourceMethod().getResourceModel();
    ResourceContext resourceContext = routingResult.getContext();

    try
    {
      Key primaryKey = resourceModel.getPrimaryKey();
      String altKeyName = resourceContext.getParameter(RestConstants.ALT_KEY_PARAM);

      if (altKeyName != null)
      {
        return ArgumentUtils.translateFromAlternativeKey(
            ArgumentUtils.parseAlternativeKey(stringKey, altKeyName, resourceModel, version),
            altKeyName, resourceModel);
      }
      else if (ComplexResourceKey.class.equals(primaryKey.getType()))
      {
        return ComplexResourceKey.parseString(stringKey, resourceModel.getKeyKeyClass(),
            resourceModel.getKeyParamsClass(), version);
      }
      else if (CompoundKey.class.equals(primaryKey.getType()))
      {
        return ArgumentUtils.parseCompoundKey(stringKey, resourceModel.getKeys(), version);
      }
      else
      {
        // The conversion of simple keys doesn't include URL decoding as the current version of Rest.li clients don't
        // encode simple keys which appear in the request body for BATCH UPDATE and BATCH PATCH requests.
        Key key = resourceModel.getPrimaryKey();
        return ArgumentUtils.convertSimpleValue(stringKey, key.getDataSchema(), key.getType());
      }
    }
    catch (InvalidAlternativeKeyException | AlternativeKeyCoercerException | PathSegment.PathSegmentSyntaxException | IllegalArgumentException e)
    {
      throw new RoutingException(String.format("Invalid key: '%s'", stringKey),
          HttpStatus.S_400_BAD_REQUEST.getCode());
    }
  }
}
