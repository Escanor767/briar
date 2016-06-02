package org.briarproject.sharing;

import org.briarproject.api.FormatException;
import org.briarproject.api.clients.ClientHelper;
import org.briarproject.api.clients.SessionId;
import org.briarproject.api.clients.BdfMessageContext;
import org.briarproject.api.data.BdfDictionary;
import org.briarproject.api.data.BdfList;
import org.briarproject.api.data.MetadataEncoder;
import org.briarproject.api.sync.Group;
import org.briarproject.api.sync.Message;
import org.briarproject.api.system.Clock;
import org.briarproject.clients.BdfMessageValidator;

import javax.inject.Inject;

import static org.briarproject.api.clients.ReadableMessageConstants.LOCAL;
import static org.briarproject.api.clients.ReadableMessageConstants.READ;
import static org.briarproject.api.clients.ReadableMessageConstants.TIMESTAMP;
import static org.briarproject.api.forum.ForumConstants.FORUM_NAME;
import static org.briarproject.api.forum.ForumConstants.FORUM_SALT;
import static org.briarproject.api.forum.ForumConstants.FORUM_SALT_LENGTH;
import static org.briarproject.api.forum.ForumConstants.MAX_FORUM_NAME_LENGTH;
import static org.briarproject.api.sharing.SharingConstants.INVITATION_MSG;
import static org.briarproject.api.sharing.SharingConstants.SESSION_ID;
import static org.briarproject.api.sharing.SharingConstants.SHARE_MSG_TYPE_ABORT;
import static org.briarproject.api.sharing.SharingConstants.SHARE_MSG_TYPE_ACCEPT;
import static org.briarproject.api.sharing.SharingConstants.SHARE_MSG_TYPE_DECLINE;
import static org.briarproject.api.sharing.SharingConstants.SHARE_MSG_TYPE_INVITATION;
import static org.briarproject.api.sharing.SharingConstants.SHARE_MSG_TYPE_LEAVE;
import static org.briarproject.api.sharing.SharingConstants.TYPE;
import static org.briarproject.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

class ForumSharingValidator extends BdfMessageValidator {

	@Inject
	ForumSharingValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock) {
		super(clientHelper, metadataEncoder, clock);
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws FormatException {

		BdfDictionary d = new BdfDictionary();
		long type = body.getLong(0);
		byte[] id = body.getRaw(1);
		checkLength(id, SessionId.LENGTH);

		if (type == SHARE_MSG_TYPE_INVITATION) {
			checkSize(body, 4, 5);

			String name = body.getString(2);
			checkLength(name, 1, MAX_FORUM_NAME_LENGTH);

			byte[] salt = body.getRaw(3);
			checkLength(salt, FORUM_SALT_LENGTH);

			d.put(FORUM_NAME, name);
			d.put(FORUM_SALT, salt);

			if (body.size() > 4) {
				String msg = body.getString(4);
				checkLength(msg, 0, MAX_MESSAGE_BODY_LENGTH);
				d.put(INVITATION_MSG, msg);
			}
		} else {
			checkSize(body, 2);
			if (type != SHARE_MSG_TYPE_ACCEPT &&
					type != SHARE_MSG_TYPE_DECLINE &&
					type != SHARE_MSG_TYPE_LEAVE &&
					type != SHARE_MSG_TYPE_ABORT) {
				throw new FormatException();
			}
		}
		// Return the metadata
		d.put(TYPE, type);
		d.put(SESSION_ID, id);
		d.put(LOCAL, false);
		d.put(TIMESTAMP, m.getTimestamp());
		d.put(READ, false);
		return new BdfMessageContext(d);
	}
}
