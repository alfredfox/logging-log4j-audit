/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.audit;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.EventLogger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.audit.annotation.Constraint;
import org.apache.logging.log4j.audit.annotation.Constraints;
import org.apache.logging.log4j.audit.annotation.MaxLength;
import org.apache.logging.log4j.audit.annotation.RequestContext;
import org.apache.logging.log4j.audit.annotation.RequestContextConstraints;
import org.apache.logging.log4j.audit.annotation.Required;
import org.apache.logging.log4j.audit.exception.AuditException;
import org.apache.logging.log4j.audit.util.NamingUtils;
import org.apache.logging.log4j.catalog.api.exception.ConstraintValidationException;
import org.apache.logging.log4j.catalog.api.plugins.ConstraintPlugins;
import org.apache.logging.log4j.message.StructuredDataMessage;
import org.apache.logging.log4j.spi.ExtendedLogger;

import static org.apache.logging.log4j.catalog.api.util.StringUtils.appendNewline;

/**
 *
 */
public class LogEventFactory {

    private static final Logger logger = LogManager.getLogger(LogEventFactory.class);
    private static final String NAME = "AuditLogger";
    private static final String FQCN = LogEventFactory.class.getName();
    private static Marker EVENT_MARKER = MarkerManager.getMarker("Audit").addParents(EventLogger.EVENT_MARKER);
    private static final ExtendedLogger LOGGER = LogManager.getContext(false).getLogger(NAME);
    private static final int DEFAULT_MAX_LENGTH = 32;

    private static final AuditExceptionHandler DEFAULT_HANDLER = (message, ex) -> {
        throw new AuditException("Error logging event " + message.getId().getName(), ex);
    };

    private static final AuditExceptionHandler NOOP_EXCEPTION_HANDLER = (message, ex) -> {
    };

    private static AuditExceptionHandler defaultExceptionHandler = DEFAULT_HANDLER;

    private static ConcurrentMap<Class<?>, List<Property>> classMap = new ConcurrentHashMap<>();

    private static ConstraintPlugins constraintPlugins = ConstraintPlugins.getInstance();

    public static void setDefaultHandler(AuditExceptionHandler exceptionHandler) {
        defaultExceptionHandler = (exceptionHandler == null) ? NOOP_EXCEPTION_HANDLER : exceptionHandler;
    }

    @SuppressWarnings("unchecked")
	public static <T> T getEvent(Class<T> intrface) {

		Class<?>[] interfaces = new Class<?>[] { intrface };

        String eventId = NamingUtils.lowerFirst(intrface.getSimpleName());
        int msgLength = intrface.getAnnotation(MaxLength.class).value();
        AuditMessage msg = new AuditMessage(eventId, msgLength);
		AuditEvent audit = (AuditEvent) Proxy.newProxyInstance(intrface
				.getClassLoader(), interfaces, new AuditProxy(msg, intrface));

		return (T) audit;
	}

    public static void logEvent(Class<?> intrface, Map<String, String> properties) {
	    logEvent(intrface, properties, DEFAULT_HANDLER);
    }

    public static void logEvent(Class<?> intrface, Map<String, String> properties, AuditExceptionHandler handler) {
        StringBuilder errors = new StringBuilder();
        validateContextConstraints(intrface, errors);

        String eventId = NamingUtils.lowerFirst(intrface.getSimpleName());
        int maxLength = intrface.getAnnotation(MaxLength.class).value();
        AuditMessage msg = new AuditMessage(eventId, maxLength);
        List<Property> props = getProperties(intrface);
        Map<String, Property> propertyMap = new HashMap<>();

        for (Property property : props ) {
            propertyMap.put(property.name, property);
            if (property.isRequired && !properties.containsKey(property.name)) {
                if (errors.length() > 0) {
                    errors.append("\n");
                }
                errors.append("Required attribute ").append(property.name).append(" is missing from ").append(eventId);
            }
            if (properties.containsKey(property.name)) {
                validateConstraints(false, property.constraints, property.name, properties, errors);
            }
        }

        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!propertyMap.containsKey(entry.getKey())) {
                if (errors.length() > 0) {
                    errors.append("Attribute ").append(entry.getKey()).append(" is not defined for ").append(eventId);
                }
            }
        }

        if (errors.length() > 0) {
            throw new ConstraintValidationException(errors.toString());
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            msg.put(entry.getKey(), entry.getValue());
        }
        logEvent(msg, handler);
    }

    public static void logEvent(AuditMessage msg, AuditExceptionHandler handler) {
        try {
            LOGGER.logIfEnabled(FQCN, Level.OFF, EVENT_MARKER, msg, null);
        } catch (Throwable ex) {
            if (handler == null) {
                handler = defaultExceptionHandler;
            }
            handler.handleException(msg, ex);
        }
    }

    public static List<String> getPropertyNames(String className) {
        Class<?> intrface = getClass(className);
        List<String> names;
        if (intrface != null) {
            List<Property> props = getProperties(intrface);
            names = new ArrayList<>(props.size());
            for (Property prop : props) {
                names.add(prop.name);
            }
        } else {
            names = new ArrayList<>();
        }
        return names;
    }

    private static List<Property> getProperties(Class<?> intrface) {
        List<Property> props = classMap.get(intrface);
        if (props != null) {
            return props;
        }
        props = new ArrayList<>();
        Method[] methods = intrface.getMethods();
        boolean isCompletionStatus = false;
        for (Method method : methods) {
            if (method.getName().startsWith("set") && !method.getName().equals("setAuditExceptionHandler")) {
                if (method.getName().equals("setCompletionStatus")) {
                    isCompletionStatus = true;
                }
                String name = NamingUtils.lowerFirst(NamingUtils.getMethodShortName(method.getName()));
                Annotation[] annotations = method.getDeclaredAnnotations();
                List<Constraint> constraints = new ArrayList<>();
                boolean isRequired = false;
                for (Annotation annotation : annotations) {
                    if (annotation instanceof Constraint) {
                        constraints.add((Constraint) annotation);
                    }
                    if (annotation instanceof Required) {
                        isRequired = true;
                    }
                }
                props.add(new Property(name, isRequired, constraints));
            }
        }
        if (!isCompletionStatus) {
            props.add(new Property("completionStatus", false, new ArrayList<>()));
        }

        classMap.putIfAbsent(intrface, props);
        return classMap.get(intrface);
    }

    private static Class<?> getClass(String className) {
        try {
            Class<?> intrface = Class.forName(className);
            if (AuditEvent.class.isAssignableFrom(intrface)) {
                return intrface;
            }
            logger.error(className + " is not an AuditEvent");
        } catch (ClassNotFoundException cnfe) {
            logger.error("Unable to locate class {}", className);
        }
        return null;
    }

	private static class AuditProxy implements InvocationHandler {

		private final AuditMessage msg;
		private final Class<?> intrface;
        private AuditExceptionHandler auditExceptionHandler = DEFAULT_HANDLER;

		AuditProxy(AuditMessage msg, Class<?> intrface) {
			this.msg = msg;
			this.intrface = intrface;
		}

        public AuditMessage getMessage() {
            return msg;
        }

		@Override
        @SuppressWarnings("unchecked")
		public Object invoke(Object o, Method method, Object[] objects)
				throws Throwable {
			if (method.getName().equals("logEvent")) {

				StringBuilder errors = new StringBuilder();
				validateContextConstraints(intrface, errors);

                StringBuilder missing = new StringBuilder();
				Method[] methods = intrface.getMethods();

				for (Method _method : methods) {
					String name = NamingUtils.lowerFirst(NamingUtils.getMethodShortName(_method.getName()));

					Annotation[] annotations = _method.getDeclaredAnnotations();
					for (Annotation annotation : annotations) {
                        if (annotation instanceof Required && msg.get(name) == null) {
                            if (missing.length() > 0) {
                                missing.append(", ");
                            }
                            missing.append(name);
                        }
					}
				}
				if (errors.length() > 0) {
				    if (missing.length() > 0) {
				        errors.append("\n");
				        errors.append("Required attributes are missing: ");
				        errors.append(missing.toString());
                    }
                } else if (missing.length() > 0) {
                    errors.append("Required attributes are missing: ");
				    errors = missing;
                }

				if (errors.length() > 0) {
					throw new ConstraintValidationException("Event " + msg.getId().getName() +
							" has errors :\n" + errors.toString());
				}

                logEvent(msg, auditExceptionHandler);
			}
            if (method.getName().equals("setCompletionStatus")) {
                String name = NamingUtils.lowerFirst(NamingUtils.getMethodShortName(method.getName()));
                if (objects == null || objects[0] == null) {
                    throw new IllegalArgumentException("Missing completion status");
                }
                msg.put(name, objects[0].toString());
            }
            if (method.getName().equals("setAuditExceptionHandler")) {
			    if (objects == null || objects[0] == null) {
                    auditExceptionHandler = NOOP_EXCEPTION_HANDLER;
                } else if (objects[0] instanceof AuditExceptionHandler) {
			        auditExceptionHandler = (AuditExceptionHandler) objects[0];
                } else {
			        throw new IllegalArgumentException(objects[0] + " is not an " + AuditExceptionHandler.class.getName());
                }
            }
			if (method.getName().startsWith("set")) {
				String name = NamingUtils.lowerFirst(NamingUtils.getMethodShortName(method.getName()));
				if (objects == null || objects[0] == null) {
				    throw new IllegalArgumentException("No value to be set for " + name);
                }

                Annotation[] annotations = method.getDeclaredAnnotations();
				Class<?> returnType = method.getReturnType();
				StringBuilder errors = new StringBuilder();
                for (Annotation annotation : annotations) {

                    if (annotation instanceof Constraints) {
                        Constraints constraints = (Constraints) annotation;
                        validateConstraints(false, constraints.value(), name, objects[0].toString(),
                                errors);
                    } else if (annotation instanceof Constraint) {
                        Constraint constraint = (Constraint) annotation;
                        constraintPlugins.validateConstraint(false, constraint.constraintType(),
                                name, objects[0].toString(), constraint.constraintValue(), errors);
                    }
                }
                if (errors.length() > 0) {
                    throw new ConstraintValidationException(errors.toString());
                }
                String result;
                if (objects[0] instanceof List) {
                    result = StringUtils.join(objects, ", ");
                } else if (objects[0] instanceof Map) {
                    StructuredDataMessage extra = new StructuredDataMessage(name, null, null);
                    extra.putAll((Map)objects[0]);
                    msg.addContent(name, extra);
                    return null;
                } else {
                    result = objects[0].toString();
                }

				msg.put(name, result);
			}

			return null;
		}
    }

    private static void validateConstraints(boolean isRequestContext, Constraint[] constraints, String name,
                                            Map<String, String> properties, StringBuilder errors) {
        String value = isRequestContext ? ThreadContext.get(name) : properties.get(name);
        validateConstraints(isRequestContext, constraints, name, value, errors);
    }

    private static void validateConstraints(boolean isRequestContext, Constraint[] constraints, String name,
                                            String value, StringBuilder errors) {
        for (Constraint constraint : constraints) {
            constraintPlugins.validateConstraint(isRequestContext, constraint.constraintType(), name, value,
                    constraint.constraintValue(), errors);
        }
    }

    private static void validateContextConstraints(Class<?> intrface, StringBuilder buffer) {
        RequestContextConstraints reqCtxConstraints = intrface.getAnnotation(RequestContextConstraints.class);
        if (reqCtxConstraints != null) {
            for (RequestContext ctx : reqCtxConstraints.value()) {
                validateContextConstraint(ctx, buffer);
            }
        } else {
            RequestContext ctx = intrface.getAnnotation(RequestContext.class);
            validateContextConstraint(ctx, buffer);
        }
    }

    private static void validateContextConstraint(RequestContext constraint, StringBuilder errors) {
        String value = ThreadContext.get(constraint.key());
        if (value != null) {
            validateConstraints(true, constraint.constraints(), constraint.key(), value, errors);
        } else if (constraint.required()) {
            appendNewline(errors);
            errors.append("ThreadContext does not contain required key ").append(constraint.key());
        }
    }

    private static boolean isBlank(String value) {
        return value != null && value.length() > 0;
    }

    private static class Property {
        private final String name;
        private final boolean isRequired;
        private final Constraint[] constraints;

        public Property(String name, boolean isRequired, List<Constraint> constraints) {
            this.name = name;
            this.constraints = constraints.toArray(new Constraint[constraints.size()]);
            this.isRequired = isRequired;
        }
    }

}
