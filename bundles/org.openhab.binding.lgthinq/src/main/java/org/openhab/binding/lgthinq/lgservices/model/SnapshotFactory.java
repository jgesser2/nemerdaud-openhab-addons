/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.lgthinq.lgservices.model;

import static org.openhab.binding.lgthinq.internal.LGThinQBindingConstants.REFRIGERATOR_SNAPSHOT_NODE_V2;
import static org.openhab.binding.lgthinq.internal.LGThinQBindingConstants.WM_SNAPSHOT_WASHER_DRYER_NODE_V2;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.lgthinq.internal.errors.LGThinqApiException;
import org.openhab.binding.lgthinq.internal.errors.LGThinqUnmarshallException;
import org.openhab.binding.lgthinq.lgservices.model.devices.ac.ACCanonicalSnapshot;
import org.openhab.binding.lgthinq.lgservices.model.devices.fridge.FridgeCanonicalSnapshot;
import org.openhab.binding.lgthinq.lgservices.model.devices.washerdryer.WasherDryerSnapshot;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@link SnapshotFactory}
 *
 * @author Nemer Daud - Initial contribution
 */
@NonNullByDefault
public class SnapshotFactory {
    private static final SnapshotFactory instance;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static {
        instance = new SnapshotFactory();
    }

    public static SnapshotFactory getInstance() {
        return instance;
    }

    /**
     * Create a Snapshot result based on snapshotData collected from LG API (V1/C2)
     *
     * @param binaryData V1: decoded returnedData
     *
     * @return returns Snapshot implementation based on device type provided
     * @throws LGThinqApiException any error.
     */
    public <S extends AbstractSnapshotDefinition> S createFromBinary(String binaryData,
            List<MonitoringBinaryProtocol> prot, Class<S> clazz)
            throws LGThinqUnmarshallException, LGThinqApiException {
        try {
            Map<String, Object> snapValues = new HashMap<>();
            byte[] data = binaryData.getBytes();
            BeanInfo beanInfo = Introspector.getBeanInfo(clazz);
            S snap = clazz.getConstructor().newInstance();
            PropertyDescriptor[] pds = beanInfo.getPropertyDescriptors();
            Map<String, PropertyDescriptor> aliasesMethod = new HashMap<>();
            for (PropertyDescriptor property : pds) {
                // all attributes of class.
                Method m = property.getReadMethod(); // getter
                if (m.isAnnotationPresent(JsonProperty.class)) {
                    String value = m.getAnnotation(JsonProperty.class).value();
                    aliasesMethod.putIfAbsent(value, property);
                }
                if (m.isAnnotationPresent(JsonAlias.class)) {
                    String[] values = m.getAnnotation(JsonAlias.class).value();
                    for (String v : values) {
                        aliasesMethod.putIfAbsent(v, property);
                    }

                }
            }
            for (MonitoringBinaryProtocol protField : prot) {
                String fName = protField.fieldName;
                int value = 0;
                for (int i = protField.startByte; i < protField.startByte + protField.length; i++) {
                    value = (value << 8) + data[i];
                }
                snapValues.put(fName, value);
                PropertyDescriptor property = aliasesMethod.get(fName);
                if (property != null) {
                    // found property. Get bit value
                    Method m = property.getWriteMethod();
                    if (m.getParameters()[0].getType() == String.class) {
                        m.invoke(snap, String.valueOf(value));
                    } else if (m.getParameters()[0].getType() == Double.class) {
                        m.invoke(snap, (double) value);
                    } else if (m.getParameters()[0].getType() == Integer.class) {
                        m.invoke(snap, value);
                    } else {
                        throw new IllegalArgumentException(
                                String.format("Parameter type not supported for this factory:%s",
                                        m.getParameters()[0].getType().toString()));
                    }
                }
            }
            snap.setRawData(snapValues);
            return snap;
        } catch (IntrospectionException | InvocationTargetException | InstantiationException | IllegalAccessException
                | NoSuchMethodException e) {
            throw new LGThinqUnmarshallException("Unexpected Error unmarshalling binary data", e);
        }
    }

    /**
     * Create a Snapshot result based on snapshotData collected from LG API (V1/C2)
     *
     * @param snapshotDataJson V1: decoded returnedData; V2: snapshot body
     * @param deviceType device type
     * @return returns Snapshot implementation based on device type provided
     * @throws LGThinqApiException any error.
     */
    public <S extends AbstractSnapshotDefinition> S createFromJson(String snapshotDataJson, DeviceTypes deviceType,
            Class<S> clazz) throws LGThinqUnmarshallException, LGThinqApiException {
        try {
            Map<String, Object> snapshotMap = objectMapper.readValue(snapshotDataJson, new TypeReference<>() {
            });
            Map<String, Object> deviceSetting = new HashMap<>();
            deviceSetting.put("deviceType", deviceType.deviceTypeId());
            deviceSetting.put("snapshot", snapshotMap);
            return createFromJson(deviceSetting, clazz);
        } catch (JsonProcessingException e) {
            throw new LGThinqUnmarshallException("Unexpected Error unmarshalling json to map", e);
        }
    }

    public <S extends AbstractSnapshotDefinition> S createFromJson(Map<String, Object> deviceSettings, Class<S> clazz)
            throws LGThinqApiException {
        DeviceTypes type = getDeviceType(deviceSettings);
        Map<String, Object> snapMap = ((Map<String, Object>) deviceSettings.get("snapshot"));
        if (snapMap == null) {
            throw new LGThinqApiException("snapshot node not present in device monitoring result.");
        }
        LGAPIVerion version = discoveryAPIVersion(snapMap, type);
        return getSnapshot(clazz, type, snapMap, version);
    }

    private <S extends AbstractSnapshotDefinition> S getSnapshot(Class<S> clazz, DeviceTypes type,
            Map<String, Object> snapMap, LGAPIVerion version) {
        S snap;
        switch (type) {
            case AIR_CONDITIONER:
            case HEAT_PUMP:
                snap = clazz.cast(objectMapper.convertValue(snapMap, ACCanonicalSnapshot.class));
                snap.setRawData(snapMap);
                return snap;
            case WASHING_TOWER:
            case WASHERDRYER_MACHINE:
                switch (version) {
                    case V1_0: {
                        snap = clazz.cast(objectMapper.convertValue(snapMap, WasherDryerSnapshot.class));
                        snap.setRawData(snapMap);
                    }
                    case V2_0: {
                        Map<String, Object> washerDryerMap = Objects.requireNonNull(
                                (Map<String, Object>) snapMap.get(WM_SNAPSHOT_WASHER_DRYER_NODE_V2),
                                "washerDryer node must be present in the snapshot");
                        snap = clazz.cast(objectMapper.convertValue(washerDryerMap, WasherDryerSnapshot.class));
                        snap.setRawData(washerDryerMap);
                        return snap;
                    }
                }
            case DRYER_TOWER:
            case DRYER:
                switch (version) {
                    case V1_0: {
                        throw new IllegalArgumentException("Version 1.0 for Washer is not supported yet.");
                    }
                    case V2_0: {
                        Map<String, Object> washerDryerMap = Objects.requireNonNull(
                                (Map<String, Object>) snapMap.get(WM_SNAPSHOT_WASHER_DRYER_NODE_V2),
                                "washerDryer node must be present in the snapshot");
                        snap = clazz.cast(objectMapper.convertValue(washerDryerMap, WasherDryerSnapshot.class));
                        snap.setRawData(snapMap);
                        return snap;
                    }
                }
            case REFRIGERATOR:
                switch (version) {
                    case V1_0: {
                        throw new IllegalArgumentException("Version 1.0 for Washer is not supported yet.");
                    }
                    case V2_0: {
                        Map<String, Object> refMap = Objects.requireNonNull(
                                (Map<String, Object>) snapMap.get(REFRIGERATOR_SNAPSHOT_NODE_V2),
                                "washerDryer node must be present in the snapshot");
                        snap = clazz.cast(objectMapper.convertValue(refMap, FridgeCanonicalSnapshot.class));
                        snap.setRawData(snapMap);
                        return snap;
                    }
                }
            default:
                throw new IllegalStateException("Unexpected capability. The type " + type + " was not implemented yet");
        }
    }

    private DeviceTypes getDeviceType(Map<String, Object> rootMap) {
        Integer deviceTypeId = (Integer) rootMap.get("deviceType");
        // device code is only present in v2 devices snapshot.
        String deviceCode = Objects.requireNonNullElse((String) rootMap.get("deviceCode"), "");
        Objects.requireNonNull(deviceTypeId, "Unexpected error. deviceType field not present in snapshot schema");
        return DeviceTypes.fromDeviceTypeId(deviceTypeId, deviceCode);
    }

    private LGAPIVerion discoveryAPIVersion(Map<String, Object> snapMap, DeviceTypes type) {
        switch (type) {
            case AIR_CONDITIONER:
            case HEAT_PUMP:
                if (snapMap.containsKey("airState.opMode")) {
                    return LGAPIVerion.V2_0;
                } else if (snapMap.containsKey("OpMode")) {
                    return LGAPIVerion.V1_0;
                } else {
                    throw new IllegalStateException(
                            "Unexpected error. Can't find key node attributes to determine ACCapability API version.");
                }
            case DRYER_TOWER:
            case DRYER:
                return LGAPIVerion.V2_0;
            case WASHING_TOWER:
            case WASHERDRYER_MACHINE:
                if (snapMap.containsKey(WM_SNAPSHOT_WASHER_DRYER_NODE_V2)) {
                    return LGAPIVerion.V2_0;
                } else if (snapMap.containsKey("State")) {
                    return LGAPIVerion.V1_0;
                } else {
                    throw new IllegalStateException(
                            "Unexpected error. Can't find key node attributes to determine ACCapability API version.");
                }
            case REFRIGERATOR:
                if (snapMap.containsKey(REFRIGERATOR_SNAPSHOT_NODE_V2)) {
                    return LGAPIVerion.V2_0;
                } else {
                    throw new IllegalStateException(
                            "Unexpected error. Can't find key node attributes to determine ACCapability API version.");
                }
            default:
                throw new IllegalStateException("Unexpected capability. The type " + type + " was not implemented yet");
        }
    }
}
