package org.briarproject.briar.android.conversation.glide;


import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.signature.ObjectKey;

import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.briar.android.BriarApplication;
import org.briarproject.briar.android.attachment.AttachmentItem;

import java.io.InputStream;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public final class BriarModelLoader
		implements ModelLoader<AttachmentItem, InputStream> {

	@Inject
	BriarDataFetcherFactory dataFetcherFactory;

	BriarModelLoader(BriarApplication app) {
		app.getApplicationComponent().inject(this);
	}

	@Override
	public LoadData<InputStream> buildLoadData(AttachmentItem model, int width,
			int height, Options options) {
		ObjectKey key = new ObjectKey(model.getMessageId());
		BriarDataFetcher dataFetcher =
				dataFetcherFactory.createBriarDataFetcher(model);
		return new LoadData<>(key, dataFetcher);
	}

	@Override
	public boolean handles(AttachmentItem model) {
		return true;
	}
}
