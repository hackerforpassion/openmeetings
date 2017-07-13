/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.core.remote;

import static org.apache.openmeetings.util.OmFileHelper.EXTENSION_MP4;
import static org.apache.openmeetings.util.OpenmeetingsVariables.CONFIG_EXT_PROCESS_TTL;
import static org.apache.openmeetings.util.OpenmeetingsVariables.CONFIG_HEADER_CSP;
import static org.apache.openmeetings.util.OpenmeetingsVariables.CONFIG_HEADER_XFRAME;
import static org.apache.openmeetings.util.OpenmeetingsVariables.EXT_PROCESS_TTL;
import static org.apache.openmeetings.util.OpenmeetingsVariables.HEADER_CSP_SELF;
import static org.apache.openmeetings.util.OpenmeetingsVariables.HEADER_XFRAME_SAMEORIGIN;
import static org.apache.openmeetings.util.OpenmeetingsVariables.webAppRootKey;
import static org.apache.openmeetings.util.OpenmeetingsVariables.wicketApplicationName;

import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.io.FileUtils;
import org.apache.openmeetings.IApplication;
import org.apache.openmeetings.core.data.conference.RoomManager;
import org.apache.openmeetings.core.data.whiteboard.WhiteboardCache;
import org.apache.openmeetings.core.data.whiteboard.WhiteboardManager;
import org.apache.openmeetings.core.remote.util.SessionVariablesUtil;
import org.apache.openmeetings.core.util.WebSocketHelper;
import org.apache.openmeetings.db.dao.basic.ConfigurationDao;
import org.apache.openmeetings.db.dao.calendar.AppointmentDao;
import org.apache.openmeetings.db.dao.label.LabelDao;
import org.apache.openmeetings.db.dao.log.ConferenceLogDao;
import org.apache.openmeetings.db.dao.record.RecordingDao;
import org.apache.openmeetings.db.dao.room.RoomDao;
import org.apache.openmeetings.db.dao.room.SipDao;
import org.apache.openmeetings.db.dao.server.ISessionManager;
import org.apache.openmeetings.db.dao.server.ServerDao;
import org.apache.openmeetings.db.dao.server.SessiondataDao;
import org.apache.openmeetings.db.dao.user.UserDao;
import org.apache.openmeetings.db.dto.room.BrowserStatus;
import org.apache.openmeetings.db.dto.room.RoomStatus;
import org.apache.openmeetings.db.dto.server.ClientSessionInfo;
import org.apache.openmeetings.db.entity.file.FileItem;
import org.apache.openmeetings.db.entity.log.ConferenceLog;
import org.apache.openmeetings.db.entity.room.Client;
import org.apache.openmeetings.db.entity.room.Room;
import org.apache.openmeetings.db.entity.room.Room.RoomElement;
import org.apache.openmeetings.db.entity.server.Server;
import org.apache.openmeetings.db.entity.server.Sessiondata;
import org.apache.openmeetings.db.entity.user.User;
import org.apache.openmeetings.db.util.AuthLevelUtil;
import org.apache.openmeetings.util.CalendarPatterns;
import org.apache.openmeetings.util.InitializationContainer;
import org.apache.openmeetings.util.OmFileHelper;
import org.apache.openmeetings.util.OpenmeetingsVariables;
import org.apache.openmeetings.util.Version;
import org.apache.openmeetings.util.message.RoomMessage;
import org.apache.openmeetings.util.message.TextRoomMessage;
import org.apache.wicket.Application;
import org.apache.wicket.util.string.Strings;
import org.red5.logging.Red5LoggerFactory;
import org.red5.server.adapter.MultiThreadedApplicationAdapter;
import org.red5.server.api.IClient;
import org.red5.server.api.IConnection;
import org.red5.server.api.Red5;
import org.red5.server.api.scope.IBasicScope;
import org.red5.server.api.scope.IBroadcastScope;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.scope.ScopeType;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IPendingServiceCallback;
import org.red5.server.api.service.IServiceCapableConnection;
import org.red5.server.api.stream.IBroadcastStream;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

public class ScopeApplicationAdapter extends MultiThreadedApplicationAdapter implements IPendingServiceCallback {
	private static final Logger _log = Red5LoggerFactory.getLogger(ScopeApplicationAdapter.class, webAppRootKey);
	private static final String OWNER_SID_PARAM = "ownerSid";
	private static final String SECURITY_CODE_PARAM = "securityCode";
	private static final String WIDTH_PARAM = "width";
	private static final String HEIGHT_PARAM = "height";
	private static final String NATIVE_SSL_PARAM = "nativeSsl";
	public static final String HIBERNATE_SCOPE = "hibernate";

	@Autowired
	private ISessionManager sessionManager;
	@Autowired
	private WhiteboardService whiteBoardService;
	@Autowired
	private WhiteboardManager whiteboardManager;
	@Autowired
	private WhiteboardCache whiteboardCache;
	@Autowired
	private RecordingService recordingService;
	@Autowired
	private ConfigurationDao cfgDao;
	@Autowired
	private AppointmentDao appointmentDao;
	@Autowired
	private SessiondataDao sessiondataDao;
	@Autowired
	private RoomManager roomManager;
	@Autowired
	private ConferenceLogDao conferenceLogDao;
	@Autowired
	private UserDao userDao;
	@Autowired
	private RoomDao roomDao;
	@Autowired
	private RecordingDao recordingDao;
	@Autowired
	private ServerDao serverDao;
	@Autowired
	private SipDao sipDao;

	private static AtomicLong broadCastCounter = new AtomicLong(0);

	@Override
	public void resultReceived(IPendingServiceCall arg0) {
		if (_log.isTraceEnabled()) {
			_log.trace("resultReceived:: {}", arg0);
		}
	}

	@Override
	public boolean appStart(IScope scope) {
		try {
			OmFileHelper.setOmHome(scope.getResource("/").getFile());
			LabelDao.initLanguageMap();

			_log.debug("webAppPath : " + OmFileHelper.getOmHome());

			// Only load this Class one time Initially this value might by empty, because the DB is empty yet
			getCryptKey();

			// init your handler here

			for (String scopeName : scope.getScopeNames()) {
				_log.debug("scopeName :: " + scopeName);
			}

			InitializationContainer.initComplete = true;
			// Init properties
			IApplication iapp = (IApplication)Application.get(wicketApplicationName);
			iapp.setXFrameOptions(cfgDao.getConfValue(CONFIG_HEADER_XFRAME, String.class, HEADER_XFRAME_SAMEORIGIN));
			iapp.setContentSecurityPolicy(cfgDao.getConfValue(CONFIG_HEADER_CSP, String.class, HEADER_CSP_SELF));
			EXT_PROCESS_TTL = cfgDao.getConfValue(CONFIG_EXT_PROCESS_TTL, Integer.class, "" + EXT_PROCESS_TTL);
			Version.logOMStarted();
			recordingDao.resetProcessingStatus(); //we are starting so all processing recordings are now errors
			sessionManager.clearCache(); // 'sticky' clients should be cleaned up from DB
		} catch (Exception err) {
			_log.error("[appStart]", err);
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> getConnParams(Object[] params) {
		if (params != null && params.length > 0) {
			return (Map<String, Object>)params[0];
		}
		return new HashMap<>();
	}

	@Override
	public boolean roomConnect(IConnection conn, Object[] params) {
		_log.debug("roomConnect : ");

		IServiceCapableConnection service = (IServiceCapableConnection) conn;
		String streamId = conn.getClient().getId();

		_log.debug("### Client connected to OpenMeetings, register Client StreamId: {} scope {}", streamId, conn.getScope().getName());

		// Set StreamId in Client
		service.invoke("setId", new Object[] { streamId }, this);

		Map<String, Object> map = conn.getConnectParams();
		String swfURL = map.containsKey("swfUrl") ? (String)map.get("swfUrl") : "";
		String tcUrl = map.containsKey("tcUrl") ? (String)map.get("tcUrl") : "";
		Map<String, Object> connParams = getConnParams(params);
		String uid = (String)connParams.get("uid");
		String securityCode = (String)connParams.get(SECURITY_CODE_PARAM);
		String ownerSid = (String)connParams.get(OWNER_SID_PARAM);
		String parentSid = (String)map.get("parentSid");
		if (parentSid == null) {
			parentSid = (String)connParams.get("parentSid");
		}
		Client rcm = new Client();
		rcm.setScope(conn.getScope().getName());
		rcm.setOwnerSid(ownerSid);
		boolean hibernate = HIBERNATE_SCOPE.equals(rcm.getScope());
		IApplication iapp = (IApplication)Application.get(wicketApplicationName);
		if (!Strings.isEmpty(securityCode)) {
			//this is for external applications like ffmpeg [OPENMEETINGS-1574]
			if (rcm.getRoomId() == null) {
				_log.warn("Trying to enter invalid scope using security code, client is rejected:: " + rcm.getRoomId());
				return rejectClient();
			}
			String _uid = null;
			for (org.apache.openmeetings.db.entity.basic.Client wcl : iapp.getOmRoomClients(rcm.getRoomId())) {
				if (wcl.getSid().equals(securityCode)) {
					_uid = wcl.getUid();
					break;
				}
			}
			if (_uid == null) {
				_log.warn("Client is not found by security id, client is rejected");
				return rejectClient();
			}
			Client parent = sessionManager.getClientByPublicSID(_uid, null);
			if (parent == null || !parent.getScope().equals(rcm.getScope())) {
				_log.warn("Security code is invalid, client is rejected");
				return rejectClient();
			}
			rcm.setUsername(parent.getUsername());
			rcm.setFirstname(parent.getFirstname());
			rcm.setLastname(parent.getLastname());
			rcm.setUserId(parent.getUserId());
			rcm.setPublicSID(UUID.randomUUID().toString());
			rcm.setSecurityCode(_uid);
			Number width = (Number)connParams.get(WIDTH_PARAM);
			Number height = (Number)connParams.get(HEIGHT_PARAM);
			if (width != null && height != null) {
				rcm.setVWidth(width.intValue());
				rcm.setVHeight(height.intValue());
			}
		}
		if (Strings.isEmpty(uid) && Strings.isEmpty(securityCode) && Strings.isEmpty(parentSid) && Strings.isEmpty(ownerSid)) {
			_log.warn("No UIDs are provided, client is rejected");
			return rejectClient();
		}
		if (hibernate && "noclient".equals(uid)) {
			return true;
		}

		if (map.containsKey("screenClient")) {
			Client parent = sessionManager.getClientByPublicSID(parentSid, null);
			if (parent == null) {
				_log.warn("Bad parent for screen-sharing client, client is rejected");
				return rejectClient();
			}
			SessionVariablesUtil.setIsScreenClient(conn.getClient());
			rcm.setUserId(parent.getUserId());
			rcm.setScreenClient(true);
			rcm.setPublicSID(UUID.randomUUID().toString());
			rcm.setStreamPublishName(parentSid);
		}
		rcm.setStreamid(conn.getClient().getId());
		if (rcm.getRoomId() == null && !hibernate) {
			_log.warn("Bad room specified, client is rejected");
			return rejectClient();
		}
		if (connParams.containsKey("mobileClient")) {
			Sessiondata sd = sessiondataDao.check(parentSid);
			if (sd.getUserId() == null && !hibernate) {
				_log.warn("Attempt of unauthorized room enter, client is rejected");
				return rejectClient();
			}
			rcm.setMobile(true);
			rcm.setUserId(sd.getUserId());
			if (rcm.getUserId() != null) {
				User u = userDao.get(rcm.getUserId());
				if (u == null) {
					_log.error("Attempt of unauthorized room enter: USER not found, client is rejected");
					return rejectClient();
				}
				rcm.setUsername(u.getLogin());
				rcm.setFirstname(u.getFirstname());
				rcm.setLastname(u.getLastname());
				rcm.setEmail(u.getAddress() == null ? null : u.getAddress().getEmail());
			}
			rcm.setSecurityCode(sd.getSessionId());
			rcm.setPublicSID(UUID.randomUUID().toString());
		}
		if (sipDao.getUid() != null && sipDao.getUid().equals(rcm.getOwnerSid())) {
			rcm.setSipTransport(true);
		}
		rcm.setUserport(conn.getRemotePort());
		rcm.setUserip(conn.getRemoteAddress());
		rcm.setSwfurl(swfURL);
		rcm.setTcUrl(tcUrl);
		rcm.setNativeSsl(Boolean.TRUE.equals(connParams.get(NATIVE_SSL_PARAM)));
		if (!Strings.isEmpty(uid)) {
			rcm.setPublicSID(uid);
		}
		rcm = sessionManager.add(iapp.updateClient(rcm, false), null);
		if (rcm == null) {
			_log.warn("Failed to create Client on room connect");
			return false;
		}

		SessionVariablesUtil.initClient(conn.getClient(), rcm.getPublicSID());
		//TODO add similar code for other connections, merge with above block
		if (map.containsKey("screenClient")) {
			//TODO add check for room rights
			User u = null;
			Long userId = rcm.getUserId();
			SessionVariablesUtil.setUserId(conn.getClient(), userId);
			if (userId != null) {
				long _uid = userId.longValue();
				u = userDao.get(_uid < 0 ? -_uid : _uid);
			}
			if (u != null) {
				rcm.setUsername(u.getLogin());
				rcm.setFirstname(u.getFirstname());
				rcm.setLastname(u.getLastname());
			}
			_log.debug("publishName :: " + rcm.getStreamPublishName());
			sessionManager.updateClientByStreamId(streamId, rcm, false, null);
		}

		// Log the User
		conferenceLogDao.add(ConferenceLog.Type.clientConnect,
				rcm.getUserId(), streamId, null, rcm.getUserip(),
				rcm.getScope());
		return true;
	}

	public Map<String, String> screenSharerAction(Map<String, Object> map) {
		Map<String, String> returnMap = new HashMap<>();
		try {
			_log.debug("-----------  screenSharerAction ENTER");
			IConnection current = Red5.getConnectionLocal();

			Client client = sessionManager.getClientByStreamId(current.getClient().getId(), null);

			if (client != null) {
				boolean changed = false;
				if (Boolean.parseBoolean("" + map.get("stopStreaming")) && client.isStartStreaming()) {
					changed = true;
					client.setStartStreaming(false);
					//Send message to all users
					sendMessageToCurrentScope("stopScreenSharingMessage", client, false);
					WebSocketHelper.sendRoom(new TextRoomMessage(client.getRoomId(), client.getUserId(), RoomMessage.Type.sharingStoped, client.getStreamPublishName()));

					returnMap.put("result", "stopSharingOnly");
				}
				if (Boolean.parseBoolean("" + map.get("stopRecording")) && client.getIsRecording()) {
					changed = true;
					client.setStartRecording(false);
					client.setIsRecording(false);

					returnMap.put("result", "stopRecordingOnly");

					recordingService.stopRecordAndSave(current.getScope(), client, null);
				}
				if (Boolean.parseBoolean("" + map.get("stopPublishing")) && client.isScreenPublishStarted()) {
					changed = true;
					client.setScreenPublishStarted(false);
					returnMap.put("result", "stopPublishingOnly");

					//Send message to all users
					sendMessageToCurrentScope("stopPublishingMessage", client, false);
				}

				if (changed) {
					sessionManager.updateClientByStreamId(client.getStreamid(), client, false, null);

					if (!client.isStartStreaming() && !client.isStartRecording() && !client.isStreamPublishStarted()) {
						returnMap.put("result", "stopAll");
					}
				}
			}
			_log.debug("-----------  screenSharerAction, return: " + returnMap);
		} catch (Exception err) {
			_log.error("[screenSharerAction]", err);
		}
		return returnMap;
	}

	public List<Client> checkScreenSharing() {
		try {
			IConnection current = Red5.getConnectionLocal();
			String streamid = current.getClient().getId();

			_log.debug("checkScreenSharing -2- " + streamid);

			List<Client> screenSharerList = new LinkedList<>();

			Client currentClient = sessionManager.getClientByStreamId(streamid, null);

			for (Client rcl : sessionManager.getClientListByRoomAll(currentClient.getRoomId())) {
				if (rcl.isStartStreaming()) {
					screenSharerList.add(rcl);
				}
			}

			return screenSharerList;

		} catch (Exception err) {
			_log.error("[checkScreenSharing]", err);
		}
		return null;
	}

	/**
	 *
	 * @param map
	 * @return returns key,value Map with multiple return values or null in case of exception
	 *
	 */
	public Map<String, Object> setConnectionAsSharingClient(Map<String, Object> map) {
		try {
			_log.debug("-----------  setConnectionAsSharingClient");
			IConnection current = Red5.getConnectionLocal();

			Client client = sessionManager.getClientByStreamId(current.getClient().getId(), null);

			if (client != null) {
				boolean startRecording = Boolean.parseBoolean("" + map.get("startRecording"));
				boolean startStreaming = Boolean.parseBoolean("" + map.get("startStreaming"));
				boolean startPublishing = Boolean.parseBoolean("" + map.get("startPublishing")) && (0 == sessionManager.getPublishingCount(client.getRoomId()));

				boolean alreadyStreaming = client.isStartStreaming();
				if (startStreaming) {
					client.setStartStreaming(true);
				}
				boolean alreadyRecording = client.isStartRecording();
				if (startRecording) {
					client.setStartRecording(true);
				}
				if (startPublishing) {
					client.setStreamPublishStarted(true);
				}

				client.setVX(Double.valueOf("" + map.get("screenX")).intValue());
				client.setVY(Double.valueOf("" + map.get("screenY")).intValue());
				client.setVWidth(Double.valueOf("" + map.get("screenWidth")).intValue());
				client.setVHeight(Double.valueOf("" + map.get("screenHeight")).intValue());
				client.setStreamPublishName("" + map.get("publishName"));
				sessionManager.updateClientByStreamId(current.getClient().getId(), client, false, null);

				Map<String, Object> returnMap = new HashMap<>();
				returnMap.put("alreadyPublished", false);

				// if is already started screen sharing, then there is no need
				// to start it again
				if (client.isScreenPublishStarted()) {
					returnMap.put("alreadyPublished", true);
				}

				_log.debug("screen x,y,width,height {},{},{},{}", client.getVX(), client.getVY(), client.getVWidth(), client.getVHeight());

				if (startStreaming) {
					if (!alreadyStreaming) {
						returnMap.put("modus", "startStreaming");

						_log.debug("start streamPublishStart Is Screen Sharing ");

						//Send message to all users
						sendMessageToCurrentScope("newScreenSharing", client, false);
						WebSocketHelper.sendRoom(new TextRoomMessage(client.getRoomId(), client.getUserId(), RoomMessage.Type.sharingStarted, client.getStreamPublishName()));
					} else {
						_log.warn("Streaming is already started for the client id=" + client.getId() + ". Second request is ignored.");
					}
				}
				if (startRecording) {
					if (!alreadyRecording) {
						returnMap.put("modus", "startRecording");

						String recordingName = "Recording " + CalendarPatterns.getDateWithTimeByMiliSeconds(new Date());

						recordingService.recordMeetingStream(current, client, recordingName, "", false);
					} else {
						_log.warn("Recording is already started for the client id=" + client.getId() + ". Second request is ignored.");
					}
				}
				if (startPublishing) {
					sendMessageToCurrentScope("startedPublishing", new Object[]{client, "rtmp://" + map.get("publishingHost") + ":1935/"
							+ map.get("publishingApp") + "/" + map.get("publishingId")}, false, true);
					returnMap.put("modus", "startPublishing");
				}
				return returnMap;
			} else {
				_log.error("[setConnectionAsSharingClient] Could not find Screen Sharing Client " + current.getClient().getId());
			}
		} catch (Exception err) {
			_log.error("[setConnectionAsSharingClient]", err);
		}
		return null;
	}

	public List<Long> listRoomBroadcast() {
		Set<Long> broadcastList = new HashSet<>();
		IConnection current = Red5.getConnectionLocal();
		String streamid = current.getClient().getId();
		for (IConnection conn : current.getScope().getClientConnections()) {
			if (conn != null) {
				Client rcl = sessionManager.getClientByStreamId(conn.getClient().getId(), null);
				if (rcl == null) {
					// continue;
				} else if (rcl.isScreenClient()) {
					// continue;
				} else {
					if (!streamid.equals(rcl.getStreamid())) {
						// It is not needed to send back
						// that event to the actuall
						// Moderator
						// as it will be already triggered
						// in the result of this Function
						// in the Client
						Long id = Long.valueOf(rcl.getBroadCastID());
						if (!broadcastList.contains(id)) {
							broadcastList.add(id);
						}
					}
				}
			}
		}
		return new ArrayList<>(broadcastList);
	}

	/**
	 * this function is invoked after a reconnect
	 *
	 * @param newPublicSID
	 */
	public boolean overwritePublicSID(String newPublicSID) {
		try {
			_log.debug("-----------  overwritePublicSID");
			IConnection current = Red5.getConnectionLocal();
			IClient c = current.getClient();
			Client currentClient = sessionManager.getClientByStreamId(c.getId(), null);
			if (currentClient == null) {
				return false;
			}
			SessionVariablesUtil.initClient(c, newPublicSID);
			currentClient.setPublicSID(newPublicSID);
			sessionManager.updateClientByStreamId(c.getId(), currentClient, false, null);
			return true;
		} catch (Exception err) {
			_log.error("[overwritePublicSID]", err);
		}
		return false;
	}

	/**
	 * Logic must be before roomDisconnect cause otherwise you cannot throw a
	 * message to each one
	 *
	 */
	@Override
	public void roomLeave(IClient client, IScope room) {
		try {
			_log.debug("[roomLeave] {} {} {} {}", client.getId(), room.getClients().size(), room.getContextPath(), room.getName());

			Client rcl = sessionManager.getClientByStreamId(client.getId(), null);

			// The Room Client can be null if the Client left the room by using
			// logicalRoomLeave
			if (rcl != null) {
				_log.debug("currentClient IS NOT NULL");
				roomLeaveByScope(rcl, room);
			}
		} catch (Exception err) {
			_log.error("[roomLeave]", err);
		}
	}

	public void roomLeaveByScope(String uid, Long roomId) {
		Client rcl = sessionManager.getClientByPublicSID(uid, null);
		IScope scope = getRoomScope("" + roomId);
		_log.debug("[roomLeaveByScope] {} {} {} {}", uid, roomId, rcl, scope);
		if (rcl != null && scope != null) {
			roomLeaveByScope(rcl, scope);
		}
	}

	/**
	 * Removes the Client from the List, stops recording, adds the Room-Leave
	 * event to running recordings, clear Polls and removes Client from any list
	 *
	 * This function is kind of private/protected as the client won't be able
	 * to call it with proper values.
	 *
	 * @param client
	 * @param scope
	 */
	public void roomLeaveByScope(Client client, IScope scope) {
		try {
			_log.debug("[roomLeaveByScope] currentClient " + client);
			Long roomId = client.getRoomId();

			if (client.isScreenClient() && client.isStartStreaming()) {
				//TODO check others/find better way
				WebSocketHelper.sendRoom(new TextRoomMessage(client.getRoomId(), client.getUserId(), RoomMessage.Type.sharingStoped, client.getStreamPublishName()));
			}

			// Remove User from Sync List's
			if (roomId != null) {
				whiteBoardService.removeUserFromAllLists(scope, client);
			}

			_log.debug("removing Username " + client.getUsername() + " "
					+ client.getConnectedSince() + " streamid: "
					+ client.getStreamid());

			// stop and save any recordings
			if (client.getIsRecording()) {
				_log.debug("*** roomLeave Current Client is Recording - stop that");
				if (client.getInterviewPodId() != null) {
					//interview, TODO need better check
					_stopInterviewRecording(client, scope);
				} else {
					recordingService.stopRecordAndSave(scope, client, null);

					// set to true and overwrite the default one cause otherwise no
					// notification is send
					client.setIsRecording(true);
				}
			}
			recordingService.stopRecordingShowForClient(scope, client);

			// Notify all clients of the same currentScope (room) with domain
			// and room except the current disconnected cause it could throw an exception
			_log.debug("currentScope " + scope);

			new MessageSender(scope, "roomDisconnect", client, this) {
				@Override
				public boolean filter(IConnection conn) {
					Client rcl = sessionManager.getClientByStreamId(conn.getClient().getId(), null);
					if (rcl == null) {
						return true;
					}
					boolean isScreen = rcl.isScreenClient();
					if (isScreen && client.getPublicSID().equals(rcl.getStreamPublishName())) {
						//going to terminate screen sharing started by this client
						((IServiceCapableConnection) conn).invoke("stopStream", new Object[] { }, callback);
					}
					return isScreen;
				}
			}.start();

			if (client.isMobile() || client.isSipTransport()) {
				IApplication app = (IApplication)Application.get(wicketApplicationName);
				app.exit(client.getPublicSID());
			}
			sessionManager.removeClient(client.getStreamid(), null);
		} catch (Exception err) {
			_log.error("[roomLeaveByScope]", err);
		}
	}

	/**
	 * This method handles the Event after a stream has been added all connected
	 * Clients in the same room will get a notification
	 *
	 */
	/* (non-Javadoc)
	 * @see org.red5.server.adapter.MultiThreadedApplicationAdapter#streamPublishStart(org.red5.server.api.stream.IBroadcastStream)
	 */
	@Override
	public void streamPublishStart(IBroadcastStream stream) {
		try {
			_log.debug("-----------  streamPublishStart");
			IConnection current = Red5.getConnectionLocal();
			final String streamid = current.getClient().getId();
			final Client c = sessionManager.getClientByStreamId(streamid, null);

			//We make a second object the has the reference to the object
			//that we will use to send to all participents
			Client clientObjectSendToSync = c;

			// Notify all the clients that the stream had been started
			_log.debug("start streamPublishStart broadcast start: " + stream.getPublishedName() + " CONN " + current);

			// In case its a screen sharing we start a new Video for that
			if (c.isScreenClient()) {
				c.setScreenPublishStarted(true);
				sessionManager.updateClientByStreamId(streamid, c, false, null);
			}
			if (!c.isMobile() && !Strings.isEmpty(c.getSecurityCode())) {
				c.setBroadCastID(Long.parseLong(stream.getPublishedName()));
				c.setAvsettings("av");
				c.setIsBroadcasting(true);
				if (c.getVWidth() == 0 || c.getVHeight() == 0) {
					c.setVWidth(320);
					c.setVHeight(240);
				}
				sessionManager.updateClientByStreamId(streamid, c, false, null);
			}

			_log.debug("newStream SEND: " + c);

			// Notify all users of the same Scope
			// We need to iterate through the streams to catch if anybody is recording
			new MessageSender(current, "newStream", clientObjectSendToSync, this) {
				@Override
				public boolean filter(IConnection conn) {
					Client rcl = sessionManager.getClientByStreamId(conn.getClient().getId(), null);

					if (rcl == null) {
						_log.debug("RCL IS NULL newStream SEND");
						return true;
					}

					_log.debug("check send to "+rcl);

					if (Strings.isEmpty(rcl.getPublicSID())) {
						_log.debug("publicSID IS NULL newStream SEND");
						return true;
					}
					if (rcl.getIsRecording()) {
						_log.debug("RCL getIsRecording newStream SEND");
						recordingService.addRecordingByStreamId(current, c, rcl.getRecordingId());
					}
					if (rcl.isScreenClient()) {
						_log.debug("RCL getIsScreenClient newStream SEND");
						return true;
					}

					if (rcl.getPublicSID().equals(c.getPublicSID())) {
						_log.debug("RCL publicSID is equal newStream SEND");
						return true;
					}
					_log.debug("RCL SEND is equal newStream SEND "+rcl.getPublicSID()+" || "+rcl.getUserport());
					return false;
				}
			}.start();
		} catch (Exception err) {
			_log.error("[streamPublishStart]", err);
		}
	}

	public IBroadcastScope getBroadcastScope(IScope scope, String name) {
		IBasicScope basicScope = scope.getBasicScope(ScopeType.BROADCAST, name);
		if (!(basicScope instanceof IBroadcastScope)) {
			return null;
		} else {
			return (IBroadcastScope) basicScope;
		}
	}

	/**
	 * This method handles the Event after a stream has been removed all
	 * connected Clients in the same room will get a notification
	 *
	 */
	/* (non-Javadoc)
	 * @see org.red5.server.adapter.MultiThreadedApplicationAdapter#streamBroadcastClose(org.red5.server.api.stream.IBroadcastStream)
	 */
	@Override
	public void streamBroadcastClose(IBroadcastStream stream) {
		// Notify all the clients that the stream had been closed
		_log.debug("start streamBroadcastClose broadcast close: " + stream.getPublishedName());
		try {
			IConnection current = Red5.getConnectionLocal();
			String streamId = current.getClient().getId();
			Client rcl = sessionManager.getClientByStreamId(streamId, null);

			if (rcl == null) {

				// In case the client has already left(kicked) this message
				// might be thrown later then the RoomLeave
				// event and the currentClient is already gone
				// The second Use-Case where the currentClient is maybe null is
				// if we remove the client because its a Zombie/Ghost

				return;

			}
			// Notify all the clients that the stream had been started
			_log.debug("streamBroadcastClose : " + rcl + " " + rcl.getStreamid());
			// this close stream event, stop the recording of this stream
			if (rcl.getIsRecording()) {
				_log.debug("***  +++++++ ######## sendClientBroadcastNotifications Any Client is Recording - stop that");
				recordingService.stopRecordingShowForClient(current.getScope(), rcl);
			}
			if (stream.getPublishedName().equals("" + rcl.getBroadCastID())) {
				rcl.setBroadCastID(-1);
				rcl.setIsBroadcasting(false);
				rcl.setAvsettings("n");
			}
			sessionManager.updateClientByStreamId(streamId, rcl, false, null);
			// Notify all clients of the same scope (room)
			sendMessageToCurrentScope("closeStream", rcl, rcl.isMobile());
		} catch (Exception e) {
			_log.error("[streamBroadcastClose]", e);
		}
	}

	@SuppressWarnings("unchecked")
	public void setNewCursorPosition(Object item) {
		try {
			IConnection current = Red5.getConnectionLocal();
			Client c = sessionManager.getClientByStreamId(current.getClient().getId(), null);

			@SuppressWarnings("rawtypes")
			Map cursor = (Map) item;
			cursor.put("streamPublishName", c.getStreamPublishName());

			sendMessageToCurrentScope("newRed5ScreenCursor", cursor, true, false);
		} catch (Exception err) {
			_log.error("[setNewCursorPosition]", err);
		}
	}

	public long removeModerator(String publicSID) {
		try {
			_log.debug("-----------  removeModerator: " + publicSID);

			Client currentClient = sessionManager.getClientByPublicSID(publicSID, null);

			if (currentClient == null) {
				return -1L;
			}
			Long roomId = currentClient.getRoomId();

			currentClient.setIsMod(false);
			// Put the mod-flag to true for this client
			sessionManager.updateClientByStreamId(currentClient.getStreamid(), currentClient, false, null);

			List<Client> currentMods = sessionManager.getCurrentModeratorByRoom(roomId);

			sendMessageToCurrentScope("setNewModeratorByList", currentMods, true);
		} catch (Exception err) {
			_log.error("[removeModerator]", err);
		}
		return -1L;
	}

	public long switchMicMuted(String publicSID, boolean mute) {
		try {
			_log.debug("-----------  switchMicMuted: " + publicSID);

			Client currentClient = sessionManager.getClientByPublicSID(publicSID, null);
			if (currentClient == null) {
				return -1L;
			}

			currentClient.setMicMuted(mute);
			sessionManager.updateClientByStreamId(currentClient.getStreamid(), currentClient, false, null);

			Map<Integer, Object> newMessage = new HashMap<>();
			newMessage.put(0, "updateMuteStatus");
			newMessage.put(1, currentClient);
			sendMessageWithClient(newMessage);
		} catch (Exception err) {
			_log.error("[switchMicMuted]", err);
		}
		return 0L;
	}

	public boolean getMicMutedByPublicSID(String publicSID) {
		try {
			Client currentClient = sessionManager.getClientByPublicSID(publicSID, null);
			if (currentClient == null) {
				return true;
			}

			//Put the mod-flag to true for this client
			return currentClient.getMicMuted();
		} catch (Exception err) {
			_log.error("[getMicMutedByPublicSID]",err);
		}
		return true;
	}

	public static long nextBroadCastId() {
		return broadCastCounter.getAndIncrement();
	}

	/**
	 * This method is used to set/update broadCastID of current client
	 *
	 * @param updateBroadcastId boolean flag
	 *
	 * @return BroadcastId in case of no errors, -1 otherwise
	 */
	public long setUserAVSettings(boolean updateBroadcastId) {
		try {
			String streamid = Red5.getConnectionLocal().getClient().getId();
			_log.debug("-----------  setUserAVSettings {}", streamid);
			Client rcl = sessionManager.getClientByStreamId(streamid, null);
			if (rcl == null) {
				_log.warn("Failed to find appropriate clients");
				return -1;
			}
			if (updateBroadcastId) {
				rcl.setBroadCastID(nextBroadCastId());
				sessionManager.updateAVClientByStreamId(streamid, rcl, null);
			}
			return rcl.getBroadCastID();
		} catch (Exception err) {
			_log.error("[setUserAVSettings]", err);
		}
		return -1;
	}

	/*
	 * checks if the user is allowed to apply for Moderation
	 */
	public boolean checkRoomValues(Long roomId) {
		try {

			// appointed meeting or moderated Room?
			Room room = roomDao.get(roomId);

			// not really - default logic
			if (!room.isAppointment() && room.isModerated()) {
				// if this is a Moderated Room then the Room can be only
				// locked off by the Moderator Bit
				List<Client> clientModeratorListRoom = sessionManager.getCurrentModeratorByRoom(roomId);

				// If there is no Moderator yet and we are asking for it
				// then deny it
				// cause at this moment, the user should wait untill a
				// Moderator enters the Room
				return clientModeratorListRoom.size() != 0;
			} else {
				// FIXME: TODO: For Rooms that are created as Appointment we
				// have to check that too
				// but I don't know yet the Logic behind it - swagner 19.06.2009
				return true;

			}
		} catch (Exception err) {
			_log.error("[checkRoomValues]", err);
		}
		return false;
	}

	/**
	 * This function is called once a User enters a Room
	 *
	 * It contains several different mechanism depending on what roomtype and
	 * what options are available for the room to find out if the current user
	 * will be a moderator of that room or not<br/>
	 * <br/>
	 * Some rules:<br/>
	 * <ul>
	 * <li>If it is a room that was created through the calendar, the user that
	 * organized the room will be moderator, the param Boolean becomeModerator
	 * will be ignored then</li>
	 * <li>In regular rooms you can use the param Boolean becomeModerator to set
	 * any user to become a moderator of the room</li>
	 * </ul>
	 * <br/>
	 * If a new moderator is detected a Push Call to all current users of the
	 * room is invoked "setNewModeratorByList" to notify them of the new
	 * moderator<br/>
	 * <br/>
	 * At the end of the mechanism a push call with the new client-object
	 * and all the informations about the new user is send to every user of the
	 * current conference room<br/>
	 * <br/>
	 *
	 * @param roomId - id of the room
	 * @param becomeModerator - is user will become moderator
	 * @param isSuperModerator - is user super moderator
	 * @param groupId - group id of the user
	 * @param colorObj - some color
	 * @return RoomStatus object
	 */
	public RoomStatus setRoomValues(Long roomId, boolean becomeModerator, boolean isSuperModerator, String colorObj) {
		try {
			_log.debug("-----------  setRoomValues");
			IConnection current = Red5.getConnectionLocal();
			String streamid = current.getClient().getId();
			Client client = sessionManager.getClientByStreamId(streamid, null);
			client.setRoomId(roomId);
			client.setRoomEnter(new Date());

			client.setUsercolor(colorObj);

			Long userId = client.getUserId();
			User u = userId == null ? null : userDao.get(userId > 0 ? userId : -userId);
			// Inject externalUserId if nothing is set yet
			if (client.getExternalUserId() == null && u != null) {
				client.setExternalUserId(u.getExternalId());
				client.setExternalUserType(u.getExternalType());
			}

			Room r = roomDao.get(roomId);
			if (!r.isHidden(RoomElement.MicrophoneStatus)) {
				client.setCanGiveAudio(true);
			}
			sessionManager.updateClientByStreamId(streamid, client, true, null); // first save to get valid room count

			// Check for Moderation LogicalRoom ENTER
			List<Client> roomClients = sessionManager.getClientListByRoom(roomId);

			// Return Object
			RoomStatus roomStatus = new RoomStatus();
			// appointed meeting or moderated Room? => Check Max Users first
			if (isSuperModerator) {
				// This can be set without checking for Moderation Flag
				client.setIsSuperModerator(isSuperModerator);
				client.setIsMod(isSuperModerator);
			} else {
				Set<Room.Right> rr = AuthLevelUtil.getRoomRight(u, r, r.isAppointment() ? appointmentDao.getByRoom(r.getId()) : null, roomClients.size());
				client.setIsSuperModerator(rr.contains(Room.Right.superModerator));
				client.setIsMod(becomeModerator || rr.contains(Room.Right.moderator));
			}
			if (client.getIsMod()) {
				// Update the Client List
				sessionManager.updateClientByStreamId(streamid, client, false, null);

				List<Client> modRoomList = sessionManager.getCurrentModeratorByRoom(client.getRoomId());

				//Sync message to everybody
				sendMessageToCurrentScope("setNewModeratorByList", modRoomList, false);
			}

			//Sync message to everybody
			sendMessageToCurrentScope("addNewUser", client, false);

			//Status object for Shared Browsing
			BrowserStatus browserStatus = (BrowserStatus)current.getScope().getAttribute("browserStatus");

			if (browserStatus == null) {
				browserStatus = new BrowserStatus();
			}

			// RoomStatus roomStatus = new RoomStatus();

			// FIXME: Rework Client Object to DTOs
			roomStatus.setClientList(roomClients);
			roomStatus.setBrowserStatus(browserStatus);

			return roomStatus;
		} catch (Exception err) {
			_log.error("[setRoomValues]", err);
		}
		return null;
	}

	/**
	 * this is set initial directly after login/loading language
	 *
	 * @param SID - id of the session
	 * @param userId - id of the user being set
	 * @param username - username of the user
	 * @param firstname - firstname of the user
	 * @param lastname - lastname of the user
	 * @return RoomClient in case of everything is OK, null otherwise
	 */
	public Client setUsernameAndSession(String SID, Long userId, String username, String firstname, String lastname) {
		try {
			_log.debug("-----------  setUsernameAndSession");
			IConnection current = Red5.getConnectionLocal();
			String streamid = current.getClient().getId();
			Client currentClient = sessionManager.getClientByStreamId(streamid, null);

			currentClient.setUsername(username);
			currentClient.setUserId(userId);
			SessionVariablesUtil.setUserId(current.getClient(), userId);
			currentClient.setUserObject(userId, username, firstname, lastname);

			// Update Session Data
			_log.debug("UDPATE SESSION " + SID + ", " + userId);
			sessiondataDao.updateUserWithoutSession(SID, userId);

			User user = userDao.get(userId);

			if (user != null) {
				currentClient.setExternalUserId(user.getExternalId());
				currentClient.setExternalUserType(user.getExternalType());
			}

			// only fill this value from User-Record
			// cause invited users have non
			// you cannot set the firstname,lastname from the UserRecord
			User us = userDao.get(userId);
			if (us != null && us.getPictureuri() != null) {
				// set Picture-URI
				currentClient.setPicture_uri(us.getPictureuri());
			}
			sessionManager.updateClientByStreamId(streamid, currentClient, false, null);
			return currentClient;
		} catch (Exception err) {
			_log.error("[setUsername]", err);
		}
		return null;
	}

	/**
	 * used by the Screen-Sharing Servlet to trigger events
	 *
	 * @param roomId
	 * @param message
	 * @return the list of room clients
	 */
	public Map<String, Client> sendMessageByRoomAndDomain(Long roomId, Object message) {
		Map<String, Client> roomClientList = new HashMap<>();
		try {

			_log.debug("sendMessageByRoomAndDomain " + roomId);

			IScope scope = getRoomScope(roomId.toString());

			new MessageSender(scope, "newMessageByRoomAndDomain", message, this) {
				@Override
				public boolean filter(IConnection conn) {
					IClient client = conn.getClient();
					return SessionVariablesUtil.isScreenClient(client);
				}
			}.start();
		} catch (Exception err) {
			_log.error("[getClientListBYRoomAndDomain]", err);
		}
		return roomClientList;
	}

	public List<Client> getCurrentModeratorList() {
		try {
			IConnection current = Red5.getConnectionLocal();
			Client client = sessionManager.getClientByStreamId(current.getClient().getId(), null);
			Long roomId = client.getRoomId();
			Room r = roomDao.get(roomId);
			if (r != null) {
				return sessionManager.getCurrentModeratorByRoom(roomId);
			}
		} catch (Exception err) {
			_log.error("[getCurrentModerator]", err);
		}
		return null;
	}

	/**
	 * This Function is triggered from the Whiteboard
	 *
	 * @param whiteboardObjParam - array of parameters being sended to whiteboard
	 * @param whiteboardId - id of whiteboard parameters will be send to
	 * @return 1 in case of no errors, -1 otherwise
	 */
	public int sendVarsByWhiteboardId(List<?> whiteboardObjParam, Long whiteboardId) {
		try {
			IConnection current = Red5.getConnectionLocal();
			Client client = sessionManager.getClientByStreamId(current.getClient().getId(), null);
			return sendToWhiteboard(client, whiteboardObjParam, whiteboardId);
		} catch (Exception err) {
			_log.error("[sendVarsByWhiteboardId]", err);
			return -1;
		}
	}

	private static Point getSize(FileItem fi) {
		Point result = new Point(0, 0);
		if (fi.getWidth() != null && fi.getHeight() != null) {
			result.x = fi.getWidth();
			result.y = fi.getHeight();
		}
		return result;
	}

	private static List<?> getWbObject(FileItem fi, String url) {
		Point size = getSize(fi);
		String type = "n/a";
		switch (fi.getType()) {
			case Image:
				type = "image";
				break;
			case Presentation:
				type = "swf";
				break;
			default:
		}
		return Arrays.asList(
				type // 0
				, url // urlname
				, "--dummy--" // baseurl
				, fi.getName() // fileName //3
				, "--dummy--" // moduleName //4
				, "--dummy--" // parentPath //5
				, "--dummy--" // room //6
				, "--dummy--" // domain //7
				, 1 // slideNumber //8
				, 0 // innerx //9
				, 0 // innery //10
				, size.x // innerwidth //11
				, size.y // innerheight //12
				, 20 // zoomlevel //13
				, size.x // initwidth //14
				, size.y // initheight //15
				, 100 // currentzoom //16 FIXME TODO
				, fi.getHash() // uniquObjectSyncName //17
				, fi.getName() // standardFileName //18
				, true // fullFit //19 FIXME TODO
				, 0 // zIndex //-8
				, null //-7
				, 0 // this.counter //-6 FIXME TODO
				, 0 // posx //-5
				, 0 // posy //-4
				, size.x // width //-3
				, size.y // height //-2
				, fi.getHash() // this.currentlayer.name //-1
				);
	}

	private static List<?> getMp4WbObject(FileItem fi, String url) {
		Point size = getSize(fi);
		return Arrays.asList(
				"flv" // 0: 'flv'
				, fi.getId() // 1: 7
				, fi.getName() // 2: 'BigBuckBunny_512kb.mp4'
				, url // 3: posterUrl
				, size.x // 4: 416
				, size.y // 5: 240
				, 0 // 6: 1 // z-index
				, fi.getHash() // 7: null //TODO
				, 0 // 8: 0 //TODO // counter
				, 0 // 9: 0 //TODO // x
				, 0 // 10: 0 //TODO // y
				, size.x // 11: 749 // width
				, size.y // 12: 739 // height
				, fi.getHash() // 13: 'flv_1469602000351'
				);
	}

	private static void copyFileToRoom(Long roomId, FileItem f) {
		try {
			if (roomId != null && f != null) {
				File mp4 = f.getFile(EXTENSION_MP4);

				File targetFolder = OmFileHelper.getStreamsSubDir(roomId);

				File target = new File(targetFolder, mp4.getName());
				if (mp4.exists() && !target.exists()) {
					FileUtils.copyFile(mp4, target, false);
				}
			}
		} catch (Exception err) {
			_log.error("[copyFileToCurrentRoom] ", err);
		}
	}

	public void sendToWhiteboard(String uid, Long wbId, FileItem fi, String url, boolean clean) {
		ClientSessionInfo csi = sessionManager.getClientByPublicSIDAnyServer(uid);
		if (csi == null) {
			_log.warn("No client was found to send Wml:: {}", uid);
			return;
		}
		Client client = csi.getRcl();

		List<?> wbObject = new ArrayList<>();
		switch (fi.getType()) {
			case Image:
				wbObject = getWbObject(fi, url);
				break;
			case Presentation:
				wbObject = getWbObject(fi, url);
				break;
			case Video:
			case Recording:
				wbObject = getMp4WbObject(fi, url);
				copyFileToRoom(client.getRoomId(), fi);
				break;
			default:
		}
		if (clean) {
			Map<String, Object> wbClear = new HashMap<>();
			wbClear.put("id", wbId);
			wbClear.put("param", Arrays.asList("whiteboard", new Date(), "clear", null));

			whiteboardCache.get(client.getRoomId(), wbId).clear();
			sendToScope(client.getRoomId(), "sendVarsToWhiteboardById", Arrays.asList(null, wbClear));
		}
		sendToWhiteboard(client, Arrays.asList("whiteboard", new Date(), "draw", wbObject), wbId);
	}

	private int sendToWhiteboard(Client client, List<?> wbObj, Long wbId) {
		try {
			// Check if this User is the Mod:
			if (client == null) {
				return -1;
			}

			Map<Integer, Object> whiteboardObj = new HashMap<>();
			int i = 0;
			for (Object obj : wbObj) {
				whiteboardObj.put(i++, obj);
			}

			Long roomId = client.getRoomId();

			// log.debug("***** sendVars: " + whiteboardObj);

			// Store event in list
			String action = whiteboardObj.get(2).toString();

			if (action.equals("deleteMindMapNodes")) {
				// Simulate Single Delete Events for z-Index
				List<?> actionObject = (List<?>) whiteboardObj.get(3);

				@SuppressWarnings("unchecked")
				List<List<?>> itemObjects = (List<List<?>>) actionObject.get(3);

				Map<Integer, Object> whiteboardTempObj = new HashMap<>();
				whiteboardTempObj.put(2, "delete");

				for (List<?> itemObject : itemObjects) {
					List<Object> tempActionObject = new ArrayList<>();
					tempActionObject.add("mindmapnode");
					tempActionObject.add(itemObject.get(0)); // z-Index -8
					tempActionObject.add(null); // simulate -7
					tempActionObject.add(null); // simulate -6
					tempActionObject.add(null); // simulate -5
					tempActionObject.add(null); // simulate -4
					tempActionObject.add(null); // simulate -3
					tempActionObject.add(null); // simulate -2
					tempActionObject.add(itemObject.get(1)); // Object-Name -1

					whiteboardTempObj.put(3, tempActionObject);

					whiteboardManager.add(roomId, whiteboardTempObj, wbId);
				}
			} else {
				whiteboardManager.add(roomId, whiteboardObj, wbId);
			}

			Map<String, Object> sendObject = new HashMap<>();
			sendObject.put("id", wbId);
			sendObject.put("param", wbObj);

			boolean showDrawStatus = getWhiteboardDrawStatus();

			sendToScope(roomId, "sendVarsToWhiteboardById", new Object[] { showDrawStatus ? client : null, sendObject });
		} catch (Exception err) {
			_log.error("[sendToWhiteboard]", err);
			return -1;
		}
		return 1;
	}

	public int sendMessage(Object newMessage) {
		sendMessageToCurrentScope("sendVarsToMessage", newMessage, false);
		return 1;
	}

	public int sendMessageAll(Object newMessage) {
		sendMessageToCurrentScope("sendVarsToMessage", newMessage, true);
		return 1;
	}

	/**
	 * send status for shared browsing to all members except self
	 * @param newMessage
	 * @return 1
	 */
	@SuppressWarnings({ "rawtypes" })
	public int sendBrowserMessageToMembers(Object newMessage) {
		try {
			IConnection current = Red5.getConnectionLocal();

			List newMessageList = (List) newMessage;

			String action = newMessageList.get(0).toString();

			BrowserStatus browserStatus = (BrowserStatus) current.getScope().getAttribute("browserStatus");

			if (browserStatus == null) {
				browserStatus = new BrowserStatus();
			}

			if (action.equals("initBrowser") || action.equals("newBrowserURL")) {
				browserStatus.setBrowserInited(true);
				browserStatus.setCurrentURL(newMessageList.get(1).toString());
			} else if (action.equals("closeBrowserURL")) {
				browserStatus.setBrowserInited(false);
			}

			current.getScope().setAttribute("browserStatus", browserStatus);

			sendMessageToCurrentScope("sendVarsToMessage", newMessage, false);

		} catch (Exception err) {
			_log.error("[sendMessage]", err);
		}
		return 1;
	}

	/**
	 * wrapper method
	 * @param newMessage
	 */
	public void sendMessageToMembers(List<?> newMessage) {
		//Sync to all users of current scope
		sendMessageToCurrentScope("sendVarsToMessage", newMessage, false);
	}

	/**
	 * General sync mechanism for all messages that are send from within the
	 * scope of the current client, but:
	 * <ul>
	 * <li>optionally do not send to self (see param: sendSelf)</li>
	 * <li>do not send to clients that are screen sharing clients</li>
	 * <li>do not send to clients that are audio/video clients (or potentially ones)</li>
	 * <li>do not send to connections where no RoomClient is registered</li>
	 * </ul>
	 *
	 * @param remoteMethodName The method to be called
	 * @param newMessage parameters
	 * @param sendSelf send to the current client as well
	 */
	public void sendMessageToCurrentScope(String remoteMethodName, Object newMessage, boolean sendSelf) {
		sendMessageToCurrentScope(remoteMethodName, newMessage, sendSelf, false);
	}

	public void sendMessageToCurrentScope(String scopeName, String remoteMethodName, Object newMessage, boolean sendSelf) {
		sendMessageToCurrentScope(scopeName, remoteMethodName, newMessage, sendSelf, false);
	}

	public void sendToScope(final Long roomId, String method, Object obj) {
		new MessageSender(getRoomScope("" + roomId), method, obj, this) {
			@Override
			public boolean filter(IConnection conn) {
				Client rcl = sessionManager.getClientByStreamId(conn.getClient().getId(), null);
				return rcl == null || rcl.isScreenClient()
						|| rcl.getRoomId() == null || !rcl.getRoomId().equals(roomId) || userDao.get(rcl.getUserId()) == null;
			}
		}.start();
	}

	/**
	 * Only temporary for load test, with return argument for the client to have a result
	 *
	 * @param remoteMethodName
	 * @param newMessage
	 * @param sendSelf
	 * @return true
	 */
	@Deprecated
	public boolean loadTestSyncMessage(String remoteMethodName, Object newMessage, boolean sendSelf) {
		sendMessageToCurrentScope(remoteMethodName, newMessage, sendSelf, false);
		return true;
	}

	/**
	 * General sync mechanism for all messages that are send from within the
	 * scope of the current client, but:
	 * <ul>
	 * <li>optionally do not send to self (see param: sendSelf)</li>
	 * <li>send to clients that are screen sharing clients based on parameter</li>
	 * <li>do not send to clients that are audio/video clients (or potentially ones)</li>
	 * <li>do not send to connections where no RoomClient is registered</li>
	 * </ul>
	 *
	 * @param method The method to be called
	 * @param msg parameters
	 * @param sendSelf send to the current client as well
	 * @param sendScreen send to the current client as well
	 */
	public void sendMessageToCurrentScope(final String method, final Object msg, final boolean sendSelf, final boolean sendScreen) {
		IConnection conn = Red5.getConnectionLocal();
		if (conn == null) {
			_log.warn("[sendMessageToCurrentScope] -> 'Unable to send message using NULL connection' {}, {}", method, msg);
			return;
		}
		sendMessageToCurrentScope(conn.getScope().getName(), method, msg, sendSelf, sendScreen);
	}

	public void sendMessageToCurrentScope(final String scopeName, final String remoteMethodName, final Object newMessage, final boolean sendSelf, final boolean sendScreen) {
		new MessageSender(getRoomScope(scopeName), remoteMethodName, newMessage, this) {
			@Override
			public boolean filter(IConnection conn) {
				IClient client = conn.getClient();
				return (!sendScreen && SessionVariablesUtil.isScreenClient(client))
						|| (!sendSelf && current != null && client.getId().equals(current.getClient().getId()));
			}
		}.start();
	}

	public static abstract class MessageSender extends Thread {
		final IScope scope;
		final IConnection current;
		final String method;
		final Object msg;
		final IPendingServiceCallback callback;

		public MessageSender(final String remoteMethodName, final Object newMessage, IPendingServiceCallback callback) {
			this((IScope)null, remoteMethodName, newMessage, callback);
		}

		public MessageSender(IScope _scope, String method, Object msg, IPendingServiceCallback callback) {
			this(Red5.getConnectionLocal(), _scope, method, msg, callback);
		}

		public MessageSender(IConnection current, String method, Object msg, IPendingServiceCallback callback) {
			this(current, null, method, msg, callback);
		}

		public MessageSender(IConnection current, IScope _scope, String method, Object msg, IPendingServiceCallback callback) {
			this.current = current;
			scope = _scope == null && current != null ? current.getScope() : _scope;
			this.method = method;
			this.msg = msg;
			this.callback = callback;
		}

		public abstract boolean filter(IConnection conn);

		@Override
		public void run() {
			try {
				if (scope == null) {
					_log.debug("[MessageSender] -> 'Unable to send message to NULL scope' {}, {}", method, msg);
				} else {
					if (_log.isTraceEnabled()) {
						_log.trace("[MessageSender] -> 'sending message' {}, {}", method, msg);
					}
					// Send to all Clients of that Scope(Room)
					int count = 0;
					for (IConnection conn : scope.getClientConnections()) {
						if (conn != null && conn instanceof IServiceCapableConnection) {
							if (filter(conn)) {
								continue;
							}
							((IServiceCapableConnection) conn).invoke(method, new Object[] { msg }, callback);
							count++;
						}
					}
					if (_log.isTraceEnabled()) {
						_log.trace("[MessageSender] -> 'sending message to {} clients, DONE' {}", count, method);
					}
				}
			} catch (Exception err) {
				_log.error(String.format("[MessageSender -> %s, %s]", method, msg), err);
			}
		}
	}

	/**
	 * wrapper method
	 * @param newMessage
	 * @return 1 in case of success, -1 otherwise
	 */
	public int sendMessageWithClient(Object newMessage) {
		try {
			sendMessageWithClientWithSyncObject(newMessage, true);

		} catch (Exception err) {
			_log.error("[sendMessageWithClient] ", err);
			return -1;
		}
		return 1;
	}

	/**
	 * wrapper method
	 * @param newMessage
	 * @param sync
	 * @return 1 in case of success, -1 otherwise
	 */
	public int sendMessageWithClientWithSyncObject(Object newMessage, boolean sync) {
		try {
			IConnection current = Red5.getConnectionLocal();
			Client currentClient = sessionManager.getClientByStreamId(current.getClient().getId(), null);

			Map<String, Object> hsm = new HashMap<>();
			hsm.put("client", currentClient);
			hsm.put("message", newMessage);

			//Sync to all users of current scope
			sendMessageToCurrentScope("sendVarsToMessageWithClient", hsm, sync);

		} catch (Exception err) {
			_log.error("[sendMessageWithClient] ", err);
			return -1;
		}
		return 1;
	}

	/**
	 * Function is used to send the kick Trigger at the moment,
	 * it sends a general message to a specific clientId
	 *
	 * @param newMessage
	 * @param clientId
	 * @return 1 in case of success, -1 otherwise
	 */
	public int sendMessageById(Object newMessage, String clientId, IScope scope) {
		try {
			_log.debug("### sendMessageById ###" + clientId);

			Map<String, Object> hsm = new HashMap<>();
			hsm.put("message", newMessage);

			// broadcast Message to specific user with id inside the same Scope
			for (IConnection conn : scope.getClientConnections()) {
				if (conn != null) {
					if (conn instanceof IServiceCapableConnection) {
						if (conn.getClient().getId().equals(clientId)) {
							((IServiceCapableConnection) conn).invoke("sendVarsToMessageWithClient", new Object[] { hsm }, this);
						}
					}
				}
			}
		} catch (Exception err) {
			_log.error("[sendMessageWithClient] ", err);
			return -1;
		}
		return 1;
	}

	/**
	 * Sends a message to a user in the same room by its clientId
	 *
	 * @param newMessage
	 * @param clientId
	 * @return 1 in case of no exceptions, -1 otherwise
	 */
	public int sendMessageWithClientById(Object newMessage, String clientId) {
		try {
			IConnection current = Red5.getConnectionLocal();
			Client currentClient = sessionManager.getClientByStreamId(current.getClient().getId(), null);

			Map<String, Object> hsm = new HashMap<>();
			hsm.put("client", currentClient);
			hsm.put("message", newMessage);

			// broadcast Message to specific user with id inside the same Scope
			for (IConnection conn : current.getScope().getClientConnections()) {
				if (conn.getClient().getId().equals(clientId)) {
					((IServiceCapableConnection) conn).invoke("sendVarsToMessageWithClient", new Object[] { hsm }, this);
				}
			}
		} catch (Exception err) {
			_log.error("[sendMessageWithClient] ", err);
			return -1;
		}
		return 1;
	}

	public void sendMessageWithClientByPublicSID(Object message, String publicSID) {
		try {
			if (publicSID == null) {
				_log.warn("'null' publicSID was passed to sendMessageWithClientByPublicSID");
				return;
			}

			// Get Room Id to send it to the correct Scope
			Client currentClient = sessionManager.getClientByPublicSID(publicSID, null);

			if (currentClient == null) {
				throw new Exception("Could not Find RoomClient on List publicSID: " + publicSID);
			}
			IScope scope = getRoomScope("" + currentClient.getRoomId());

			// log.debug("scopeHibernate "+scopeHibernate);

			if (scope != null) {
				// Notify the clients of the same scope (room) with userId

				for (IConnection conn : scope.getClientConnections()) {
					IClient client = conn.getClient();
					if (SessionVariablesUtil.isScreenClient(client)) {
						// screen sharing clients do not receive events
						continue;
					}

					if (publicSID.equals(SessionVariablesUtil.getPublicSID(client))) {
						((IServiceCapableConnection) conn).invoke("newMessageByRoomAndDomain", new Object[] { message }, this);
					}
				}
			} else {
				// Scope not yet started
			}
		} catch (Exception err) {
			_log.error("[sendMessageWithClientByPublicSID] ", err);
		}
	}

	/**
	 * @deprecated this method should be reworked to use a single SQL query in
	 *             the cache to get any client in the current room that is
	 *             recording instead of iterating through connections!
	 * @return true in case there is recording session, false otherwise, null if any exception happend
	 */
	@Deprecated
	public boolean getInterviewRecordingStatus() {
		try {
			IConnection current = Red5.getConnectionLocal();

			for (IConnection conn : current.getScope().getClientConnections()) {
				if (conn != null) {
					Client rcl = sessionManager.getClientByStreamId(conn.getClient().getId(), null);

					if (rcl.getIsRecording()) {
						return true;
					}
				}
			}
		} catch (Exception err) {
			_log.error("[getInterviewRecordingStatus]", err);
		}
		return false;
	}

	/**
	 * @deprecated @see {@link ScopeApplicationAdapter#getInterviewRecordingStatus()}
	 * @return - false if there were existing recording, true if recording was started successfully, null if any exception happens
	 */
	@Deprecated
	public boolean startInterviewRecording() {
		try {
			_log.debug("-----------  startInterviewRecording");
			IConnection current = Red5.getConnectionLocal();

			for (IConnection conn : current.getScope().getClientConnections()) {
				if (conn != null) {
					Client rcl = sessionManager.getClientByStreamId(conn.getClient().getId(), null);

					if (rcl != null && rcl.getIsRecording()) {
						return false;
					}
				}
			}
			Client current_rcl = sessionManager.getClientByStreamId(current.getClient().getId(), null);

			// Also set the Recording Flag to Record all Participants that enter
			// later
			current_rcl.setIsRecording(true);
			sessionManager.updateClientByStreamId(current.getClient().getId(), current_rcl, false, null);

			Map<String, String> interviewStatus = new HashMap<>();
			interviewStatus.put("action", "start");

			for (IConnection conn : current.getScope().getClientConnections()) {
				if (conn != null) {
					IClient client = conn.getClient();
					if (SessionVariablesUtil.isScreenClient(client)) {
						// screen sharing clients do not receive events
						continue;
					}

					((IServiceCapableConnection) conn).invoke("interviewStatus", new Object[] { interviewStatus }, this);
					_log.debug("-- startInterviewRecording " + interviewStatus);
				}
			}
			String recordingName = "Interview " + CalendarPatterns.getDateWithTimeByMiliSeconds(new Date());

			recordingService.recordMeetingStream(current, current_rcl, recordingName, "", true);

			return true;
		} catch (Exception err) {
			_log.debug("[startInterviewRecording]", err);
		}
		return false;
	}

	@SuppressWarnings({ "rawtypes" })
	public boolean sendRemoteCursorEvent(final String streamid, Map messageObj) {
		new MessageSender("sendRemoteCursorEvent", messageObj, this) {

			@Override
			public boolean filter(IConnection conn) {
				IClient client = conn.getClient();
				return !SessionVariablesUtil.isScreenClient(client) || !conn.getClient().getId().equals(streamid);
			}
		}.start();
		return true;
	}

	private Long checkRecordingClient(IConnection conn) {
		Long recordingId = null;
		if (conn != null) {
			Client rcl = sessionManager.getClientByStreamId(conn.getClient().getId(), null);
			if (rcl != null && rcl.getIsRecording()) {
				rcl.setIsRecording(false);
				recordingId = rcl.getRecordingId();
				rcl.setRecordingId(null);

				// Reset the Recording Flag to Record all
				// Participants that enter later
				sessionManager.updateClientByStreamId(conn.getClient().getId(), rcl, false, null);
			}
		}
		return recordingId;
	}

	/**
	 * Stop the recording of the streams and send event to connected users of scope
	 *
	 * @return true if interview was found
	 */
	public boolean stopInterviewRecording() {
		IConnection current = Red5.getConnectionLocal();
		Client currentClient = sessionManager.getClientByStreamId(current.getClient().getId(), null);
		return _stopInterviewRecording(currentClient, current.getScope());
	}

	/**
	 * Stop the recording of the streams and send event to connected users of scope
	 *
	 * @return true if interview was found
	 */
	private boolean _stopInterviewRecording(Client currentClient, IScope currentScope) {
		try {
			_log.debug("-----------  stopInterviewRecording");
			Long clientRecordingId = currentClient.getRecordingId();

			for (IConnection conn : currentScope.getClientConnections()) {
				Long recordingId = checkRecordingClient(conn);
				if (recordingId != null) {
					clientRecordingId = recordingId;
				}
			}
			if (clientRecordingId == null) {
				_log.debug("stopInterviewRecording:: unable to find recording client");
				return false;
			}

			recordingService.stopRecordAndSave(scope, currentClient, clientRecordingId);

			Map<String, String> interviewStatus = new HashMap<>();
			interviewStatus.put("action", "stop");

			sendMessageToCurrentScope("interviewStatus", interviewStatus, true);
			return true;

		} catch (Exception err) {
			_log.debug("[stopInterviewRecording]", err);
		}
		return false;
	}

	/**
	 * Get all ClientList Objects of that room and domain Used in
	 * lz.applyForModeration.lzx
	 *
	 * @return all ClientList Objects of that room
	 */
	public List<Client> getClientListScope() {
		try {
			IConnection current = Red5.getConnectionLocal();
			Client currentClient = sessionManager.getClientByStreamId(current.getClient().getId(), null);

			return sessionManager.getClientListByRoom(currentClient.getRoomId());
		} catch (Exception err) {
			_log.debug("[getClientListScope]", err);
		}
		return new ArrayList<>();
	}

	private boolean getWhiteboardDrawStatus() {
		return cfgDao.getWhiteboardDrawStatus();
	}

	public String getCryptKey() {
		return cfgDao.getCryptKey();
	}

	public IScope getRoomScope(String room) {
		if (Strings.isEmpty(room)) {
			return null;
		} else {
			IScope globalScope = getContext().getGlobalScope();
			IScope webAppKeyScope = globalScope.getScope(OpenmeetingsVariables.webAppRootKey);

			return webAppKeyScope.getScope(room);
		}
	}

	/*
	 * SIP transport methods
	 */

	private List<Long> getVerifiedActiveRoomIds(Server s) {
		List<Long> result = new ArrayList<>(sessionManager.getActiveRoomIdsByServer(s));
		//verify
		for (Iterator<Long> i = result.iterator(); i.hasNext();) {
			Long id = i.next();
			List<Client> rcs = sessionManager.getClientListByRoom(id);
			if (rcs.size() == 0 || (rcs.size() == 1 && rcs.get(0).isSipTransport())) {
				i.remove();
			}
		}
		return result.isEmpty() ? result : roomDao.getSipRooms(result);
	}

	private String getSipTransportLastname(Long roomId) {
		return getSipTransportLastname(roomManager.getSipConferenceMembersNumber(roomId));
	}

	private static String getSipTransportLastname(Integer c) {
		return (c != null && c > 0) ? "(" + (c - 1) + ")" : "";
	}

	public String getSipNumber(Double roomId) {
		Room r = roomDao.get(roomId.longValue());
		if (r != null && r.getConfno() != null) {
			_log.debug("getSipNumber: roomId: {}, sipNumber: {}", new Object[]{roomId, r.getConfno()});
			return r.getConfno();
		}
		return null;
	}

	public List<Long> getActiveRoomIds() {
		Set<Long> ids = new HashSet<>();
		ids.addAll(getVerifiedActiveRoomIds(null));
		for (Server s : serverDao.getActiveServers()) {
			ids.addAll(getVerifiedActiveRoomIds(s));
		}
		return new ArrayList<>(ids);
	}

	public synchronized int updateSipTransport() {
		_log.debug("-----------  updateSipTransport");
		IConnection current = Red5.getConnectionLocal();
		String streamid = current.getClient().getId();
		Client client = sessionManager.getClientByStreamId(streamid, null);
		Long roomId = client.getRoomId();
		Integer count = roomManager.getSipConferenceMembersNumber(roomId);
		String newNumber = getSipTransportLastname(count);
		_log.debug("getSipConferenceMembersNumber: " + newNumber);
		if (!newNumber.equals(client.getLastname())) {
			IApplication iapp = (IApplication)Application.get(wicketApplicationName);
			org.apache.openmeetings.db.entity.basic.Client cl = iapp.getOmOnlineClient(client.getPublicSID());
			cl.getUser().setLastname(newNumber);
			client.setLastname(newNumber);
			sessionManager.updateClientByStreamId(streamid, client, false, null);
			_log.debug("updateSipTransport: {}, {}, {}, {}, {}", new Object[] { client.getPublicSID(), client.getRoomId(),
					client.getFirstname(), client.getLastname(), client.getAvsettings() });
			WebSocketHelper.sendRoom(new TextRoomMessage(client.getRoomId(), client.getUserId(), RoomMessage.Type.rightUpdated, client.getPublicSID()));
			sendMessageWithClient(new String[] { "personal", client.getFirstname(), client.getLastname() });
		}
		return count != null && count > 0 ? count - 1 : 0;
	}

	public void setSipTransport(String broadCastId) {
		_log.debug("-----------  setSipTransport");
		IConnection current = Red5.getConnectionLocal();
		String streamid = current.getClient().getId();
		// Notify all clients of the same scope (room)
		Client c = sessionManager.getClientByStreamId(streamid, null);
		IApplication iapp = (IApplication)Application.get(wicketApplicationName);
		org.apache.openmeetings.db.entity.basic.Client cl = iapp.getOmOnlineClient(c.getPublicSID());
		String newNumber = getSipTransportLastname(c.getRoomId());
		cl.getUser().setLastname(newNumber);
		c.setLastname(newNumber);
		c.setBroadCastID(Long.parseLong(broadCastId));
		sessionManager.updateClientByStreamId(streamid, c, false, null);

		WebSocketHelper.sendRoom(new TextRoomMessage(c.getRoomId(), c.getUserId(), RoomMessage.Type.rightUpdated, c.getPublicSID()));
		sendMessageToCurrentScope("addNewUser", c, false);
	}
}