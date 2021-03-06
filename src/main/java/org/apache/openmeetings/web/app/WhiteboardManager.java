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
package org.apache.openmeetings.web.app;

import static org.apache.openmeetings.db.util.ApplicationHelper.ensureApplication;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getDefaultLang;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getWebAppRootKey;
import static org.red5.logging.Red5LoggerFactory.getLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.apache.openmeetings.core.util.WebSocketHelper;
import org.apache.openmeetings.db.dao.label.LabelDao;
import org.apache.openmeetings.db.dto.room.Whiteboard;
import org.apache.openmeetings.db.dto.room.Whiteboards;
import org.apache.openmeetings.db.entity.file.BaseFileItem;
import org.apache.openmeetings.db.entity.room.Room;
import org.apache.openmeetings.db.entity.room.RoomFile;
import org.apache.openmeetings.db.entity.user.User;
import org.apache.openmeetings.db.manager.IWhiteboardManager;
import org.apache.openmeetings.db.util.ws.RoomMessage;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import com.hazelcast.util.function.Consumer;

/**
 * Hazelcast based Whiteboard manager
 *
 * @author sebawagner
 *
 */
@Component
public class WhiteboardManager implements IWhiteboardManager {
	private static final Logger log = getLogger(WhiteboardManager.class, getWebAppRootKey());
	private final Map<Long, Whiteboards> onlineWbs = new ConcurrentHashMap<>();
	private static final String WBS_KEY = "WBS_KEY";

	@Autowired
	private Application app;

	private IMap<Long, Whiteboards> map() {
		return app.hazelcast.getMap(WBS_KEY);
	}

	@PostConstruct
	void init() {
		map().addEntryListener(new WbListener(), true);
	}

	private static String getDefaultName(Long langId, int num) {
		StringBuilder sb = new StringBuilder(LabelDao.getString("615", langId));
		if (num > 0) {
			sb.append(" ").append(num);
		}
		return sb.toString();
	}

	private boolean contains(Long roomId) {
		return onlineWbs.containsKey(roomId);
	}

	@Override
	public void clean(Long roomId, Long userId) {
		if (roomId == null) {
			return;
		}
		try {
			if (contains(roomId) && map().tryLock(roomId, 1, TimeUnit.SECONDS)) {
				try {
					onlineWbs.remove(roomId);
					map().delete(roomId);
				} finally {
					map().unlock(roomId);
				}
			}
			new Thread(() -> {
				ensureApplication();
				User u = new User();
				u.setId(userId);
				WebSocketHelper.sendRoom(new RoomMessage(roomId, u, RoomMessage.Type.wbReload));
			}).start();
		} catch (InterruptedException e) {
			log.warn("Unexpected exception while map clean-up", e);
		}
	}

	@Override
	public Whiteboards get(Long roomId) {
		return get(roomId, null);
	}

	private Whiteboards getOrCreate(Long roomId, Consumer<Whiteboards> consumer) {
		if (roomId == null) {
			return null;
		}
		Whiteboards wbs = onlineWbs.get(roomId);
		if (wbs == null) {
			wbs = new Whiteboards(roomId);
			if (consumer != null) {
				consumer.accept(wbs);
			}
		}
		return wbs;
	}

	public Map<Long, List<BaseFileItem>> get(Room r, Long langId) {
		Map<Long, List<BaseFileItem>> result = new HashMap<>();
		if (!contains(r.getId()) && r.getFiles() != null && !r.getFiles().isEmpty()) {
			if (map().tryLock(r.getId())) {
				try {
					TreeMap<Long, List<BaseFileItem>> files = new TreeMap<>();
					for (RoomFile rf : r.getFiles()) {
						List<BaseFileItem> bfl = files.get(rf.getWbIdx());
						if (bfl == null) {
							files.put(rf.getWbIdx(), new ArrayList<>());
							bfl = files.get(rf.getWbIdx());
						}
						bfl.add(rf.getFile());
					}
					Whiteboards wbs = getOrCreate(r.getId(), null);
					for (Map.Entry<Long, List<BaseFileItem>> e : files.entrySet()) {
						Whiteboard wb = add(wbs, langId);
						wbs.setActiveWb(wb.getId());
						result.put(wb.getId(), e.getValue());
					}
					update(wbs);
				} finally {
					map().unlock(r.getId());
				}
			}
		}
		return result;
	}

	public Whiteboards get(Long roomId, Long langId) {
		Whiteboards wbs = getOrCreate(roomId, inWbs -> {
			if (inWbs.getWhiteboards().isEmpty()) {
				Whiteboard wb = add(inWbs, langId);
				inWbs.setActiveWb(wb.getId());
				update(inWbs);
			}
		});
		if (wbs == null) {
			return null;
		}
		return wbs;
	}

	public Set<Entry<Long, Whiteboard>> list(long roomId) {
		Whiteboards wbs = get(roomId);
		return wbs.getWhiteboards().entrySet();
	}

	public Whiteboard add(long roomId, Long langId) {
		Whiteboards wbs = get(roomId);
		Whiteboard wb = add(wbs, langId);
		update(wbs);
		return wb;
	}

	private static Whiteboard add(Whiteboards wbs, Long langId) {
		Whiteboard wb = new Whiteboard(getDefaultName(langId == null ? getDefaultLang() : langId, wbs.count()));
		wbs.add(wb);
		return wb;
	}

	public Whiteboard clear(long roomId, Long wbId) {
		Whiteboards wbs = get(roomId);
		Whiteboard wb = wbs.get(wbId);
		if (wb != null) {
			wb.clear();
			update(wbs);
		}
		return wb;
	}

	public Whiteboard remove(long roomId, Long wbId) {
		Whiteboards wbs = get(roomId);
		Whiteboard wb = wbs.getWhiteboards().remove(wbId);
		update(wbs);
		return wb;
	}

	public void activate(long roomId, Long wbId) {
		Whiteboards wbs = get(roomId);
		wbs.setActiveWb(wbId);
		update(wbs);
	}

	public void update(long roomId, Whiteboard wb) {
		Whiteboards wbs = get(roomId);
		wbs.update(wb);
		update(wbs);
	}

	private void update(Whiteboards wbs) {
		onlineWbs.put(wbs.getRoomId(), wbs);
		new Thread(() -> map().put(wbs.getRoomId(), wbs)).start();
	}

	public class WbListener implements
			EntryAddedListener<Long, Whiteboards>
			, EntryUpdatedListener<Long, Whiteboards>
			, EntryRemovedListener<Long, Whiteboards>
	{
		@Override
		public void entryAdded(EntryEvent<Long, Whiteboards> event) {
			log.trace("WbListener::Add");
			onlineWbs.put(event.getKey(), event.getValue());
		}

		@Override
		public void entryUpdated(EntryEvent<Long, Whiteboards> event) {
			log.trace("WbListener::Update");
			onlineWbs.put(event.getKey(), event.getValue());
		}

		@Override
		public void entryRemoved(EntryEvent<Long, Whiteboards> event) {
			log.trace("WbListener::Remove");
			onlineWbs.remove(event.getKey());
		}
	}
}
