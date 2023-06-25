/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
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
package org.openhab.binding.elerotransmitterstick.internal.handler;

import org.openhab.binding.elerotransmitterstick.internal.stick.ResponseStatus;

/**
 * The {@link StatusListener} is an interface for notifying interested listeners about
 * status changes of elero channels.
 *
 * @author Volker Bier - Initial contribution
 */
public interface StatusListener {
    void statusChanged(int channelId, ResponseStatus status);
}
