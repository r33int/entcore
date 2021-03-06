/* Copyright © "Open Digital Education", 2014
 *
 * This program is published by "Open Digital Education".
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https://opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.

 *
 */

package org.entcore.directory.services.impl;

import static org.entcore.common.neo4j.Neo4jResult.fullNodeMergeHandler;
import static org.entcore.common.neo4j.Neo4jUtils.nodeSetPropertiesFromJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import org.entcore.common.bus.WorkspaceHelper;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.StatementsBuilder;
import org.entcore.common.storage.Storage;
import org.entcore.common.utils.FileUtils;
import org.entcore.common.utils.StringUtils;
import org.entcore.directory.services.UserBookService;

import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.Utils;
import fr.wseduc.webutils.http.ETag;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class DefaultUserBookService implements UserBookService {

	private final Neo4j neo = Neo4j.getInstance();
	private final Storage avatarStorage;
	private final WorkspaceHelper wsHelper;

	public DefaultUserBookService(Storage avatarStorage, WorkspaceHelper wsHelper) {
		super();
		this.avatarStorage = avatarStorage;
		this.wsHelper = wsHelper;
	}

	private Optional<String> getPictureIdForUserbook(JsonObject userBook) {
		String picturePath = userBook.getString("picture");
		if (StringUtils.isEmpty(picturePath)) {
			return Optional.empty();
		}
		//it is not picture id but user id
		if(picturePath.startsWith("/userbook/avatar/")) {
			return Optional.empty();
		}
		String[] picturePaths = picturePath.split("/");
		String pictureId = picturePaths[picturePaths.length - 1];
		return Optional.ofNullable(pictureId);
	}

	private String avatarFileNameFromUserId(String fileId, Optional<String> size) {
		// Filename ends with fileId to keep thumbs in the same folder
		return size.isPresent() ? String.format("%s-%s", size.get(), fileId) : fileId;
	}

	public void cleanAvatarCache(List<String> usersId, final Handler<Boolean> handler) {
		@SuppressWarnings("rawtypes")
		List<Future> futures = new ArrayList<>();
		for (String u : usersId) {
			Future<Boolean> future = Future.future();
			futures.add(future);
			futures.add(cleanAvatarCache(u));
		}
		CompositeFuture.all(futures).setHandler(finishRes -> handler.handle(finishRes.succeeded()));
	}

	private Future<Boolean> cleanAvatarCache(String userId) {
		Future<Boolean> future = Future.future();
		this.avatarStorage.findByFilenameEndingWith(userId, res -> {
			if (res.succeeded() && res.result().size() > 0) {
				this.avatarStorage.removeFiles(res.result(), removeRes -> {
					future.complete(true);
				});
			} else {
				future.complete(false);
			}
		});
		return future;
	}

	private Future<Boolean> cacheAvatarFromUserBook(String userId, Optional<String> pictureId, Boolean remove) {
		// clean avatar when changing or when removing
		Future<Boolean> futureClean = (pictureId.isPresent() || remove) ? cleanAvatarCache(userId)
				: Future.succeededFuture();
		return futureClean.compose(res -> {
			if (!pictureId.isPresent()) {
				return Future.succeededFuture();
			}
			Future<Boolean> futureCopy = Future.future();
			this.wsHelper.getDocument(pictureId.get(), resDoc -> {
				if (resDoc.succeeded() && "ok".equals(resDoc.result().body().getString("status"))) {
					JsonObject document = resDoc.result().body().getJsonObject("result");
					String fileId = document.getString("file");
					// Extensions are not used by storage
					String defaultFilename = avatarFileNameFromUserId(userId, Optional.empty());
					//
					JsonObject thumbnails = document.getJsonObject("thumbnails", new JsonObject());
					Map<String, String> filenamesByIds = new HashMap<>();
					filenamesByIds.put(fileId, defaultFilename);

					for (String size : thumbnails.fieldNames()) {
						filenamesByIds.put(thumbnails.getString(size),
								avatarFileNameFromUserId(userId, Optional.of(size)));
					}
					// TODO avoid buffer to improve performances and avoid cache every time
					List<Future> futures = new ArrayList<>();
					for (Entry<String, String> entry : filenamesByIds.entrySet()) {
						String cFileId = entry.getKey();
						String cFilename = entry.getValue();
						Future<JsonObject> future = Future.future();
						futures.add(future);
						this.wsHelper.readFile(cFileId, buffer -> {
							if (buffer != null) {
								this.avatarStorage.writeBuffer(FileUtils.stripExtension(cFilename), buffer, "",
										cFilename, wRes -> {
											future.complete(wRes);
										});
							} else {
								future.fail("Cannot read file from workspace storage. ID =: " + cFileId);
							}
						});
					}
					//
					CompositeFuture.all(futures).setHandler(finishRes -> futureCopy.complete(finishRes.succeeded()));
				}
			});
			return futureCopy;
		});

	}

	@Override
	public void update(String userId, JsonObject userBook, final Handler<Either<String, JsonObject>> result) {
		JsonObject u = Utils.validAndGet(userBook, UPDATE_USERBOOK_FIELDS, Collections.<String>emptyList());
		if (Utils.defaultValidationError(u, result, userId))
			return;
		// OVERRIDE AVATAR URL
		Optional<String> pictureId = getPictureIdForUserbook(userBook);
		if (pictureId.isPresent()) {
			String fileId = avatarFileNameFromUserId(userId, Optional.empty());
			u.put("picture", "/userbook/avatar/" + fileId);
		}

		StatementsBuilder b = new StatementsBuilder();
		String query = "MATCH (u:`User` { id : {id}})-[:USERBOOK]->(ub:UserBook) WITH ub.picture as oldpic,ub,u SET "
				+ nodeSetPropertiesFromJson("ub", u);
		query += " RETURN oldpic,ub.picture as picture";
		boolean updateUserBook = u.size() > 0;
		if (updateUserBook) {
			b.add(query, u.put("id", userId));
		}
		String q2 = "MATCH (u:`User` { id : {id}})-[:USERBOOK]->(ub:UserBook)"
				+ "-[:PUBLIC|PRIVE]->(h:`Hobby` { category : {category}}) " + "SET h.values = {values} ";
		JsonArray hobbies = userBook.getJsonArray("hobbies");
		if (hobbies != null) {
			for (Object o : hobbies) {
				if (!(o instanceof JsonObject))
					continue;
				JsonObject j = (JsonObject) o;
				b.add(q2, j.put("id", userId));
			}
		}
		neo.executeTransaction(b.build(), null, true, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> r) {
				if ("ok".equals(r.body().getString("status"))) {
					if (updateUserBook) {
						JsonArray results = r.body().getJsonArray("results", new JsonArray());
						JsonArray firstStatement = results.getJsonArray(0);
						if (firstStatement != null && firstStatement.size() > 0) {
							JsonObject object = firstStatement.getJsonObject(0);
							String picture = object.getString("picture", "");
							cacheAvatarFromUserBook(userId, pictureId, StringUtils.isEmpty(picture)).setHandler(e -> {
								if (e.succeeded()) {
									result.handle(new Either.Right<String, JsonObject>(new JsonObject()));
								} else {
									result.handle(new Either.Left<String, JsonObject>(
											r.body().getString("message", "update.error")));
								}
							});
						}
					} else {
						result.handle(new Either.Right<String, JsonObject>(new JsonObject()));
					}
				} else {
					result.handle(new Either.Left<String, JsonObject>(r.body().getString("message", "update.error")));
				}
			}
		});
	}

	private Future<Boolean> sendAvatar(HttpServerRequest request, String fileId) {
		Future<Boolean> future = Future.future();
		// file storage doesnt keep extension
		JsonObject meta = new JsonObject().put("content-type", "image/*");
		this.avatarStorage.fileStats(fileId, stats -> {
			if (stats.succeeded()) {
				Date modified = stats.result().getLastModified();
				boolean hasBeenModified = HttpHeaderUtils.checkIfModifiedSince(request.headers(), modified);
				boolean hasChangedEtag = !ETag.check(request, fileId);
				HttpHeaderUtils.addHeaderLastModified(request.response().headers(), modified);
				// check if file is modified or fileid has changed
				if (hasBeenModified || hasChangedEtag) {
					// TODO send file renvoie tout le chemin de fichier dans l ETAG?
					this.avatarStorage.sendFile(fileId, fileId, request, true, meta);
					future.complete(true);
				} else {
					Renders.notModified(request);
					future.complete(true);
				}
			} else {
				future.complete(false);
			}
		});
		return future;
	}

	@Override
	public void getAvatar(String userId, Optional<String> size, String defaultAvatarDirty, HttpServerRequest request) {
		String fileIdSized = avatarFileNameFromUserId(userId, size);
		sendAvatar(request, fileIdSized)// try with size
				.compose(success -> {// try without size
					if (success) {
						return Future.succeededFuture(true);
					} else {
						if (size.isPresent()) {// try without size
							String fileIdUnsized = avatarFileNameFromUserId(userId, Optional.empty());
							return sendAvatar(request, fileIdUnsized);
						} else {// without size already tried
							return Future.succeededFuture(false);
						}
					}
				}).compose(success -> {// try default
					if (success) {
						return Future.succeededFuture(true);
					} else {
						String fidIdDefault = FileUtils.stripExtension(defaultAvatarDirty);
						return sendAvatar(request, fidIdDefault);
					}
				}).setHandler(res -> {
					if (res.failed() || !res.result()) {// could not found any img
						Renders.notFound(request);
					}
				});
	}

	@Override
	public void get(String userId, Handler<Either<String, JsonObject>> result) {
		String query = "MATCH (u:`User` { id : {id}})-[:USERBOOK]->(ub: UserBook)"
				+ "OPTIONAL MATCH ub-[:PUBLIC|PRIVE]->(h:Hobby) " + "RETURN ub, COLLECT(h) as hobbies ";
		neo.execute(query, new JsonObject().put("id", userId), fullNodeMergeHandler("ub", result, "hobbies"));
	}

}
