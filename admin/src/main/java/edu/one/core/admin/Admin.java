package edu.one.core.admin;

import edu.one.core.infra.Controller;
import org.vertx.java.core.Handler;
import org.vertx.java.core.http.HttpServerRequest;

public class Admin extends Controller {

	@Override
	public void start() throws Exception {
		super.start();

		rm.get("/admin", new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				renderView(request, config);
			}
		});

		rm.get("/logout", new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				request.response.putHeader("Set-Cookie", "oneID=");
				redirect(request, "localhost:8009", "/login");
			}
		});

	}

}
