package org.hucompute.textimager.uima.biofid.flair;

import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.MetaDataStringField;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.dkpro.core.api.resources.MappingProvider;
import org.hucompute.textimager.uima.base.RestAnnotatorParallel;
import org.json.JSONArray;
import org.json.JSONObject;
import org.texttechnologylab.annotation.NamedEntity;
import org.texttechnologylab.annotation.type.Taxon;

import java.util.ArrayList;
import java.util.Collection;

import static java.lang.Integer.parseInt;

public class BiofidFlair extends RestAnnotatorParallel {
	protected MappingProvider mappingProvider;

	@Override
	protected String getAnnotatorVersion() {
		return "0.1";
	}

	@Override
	protected String getModelName() {
		return "";
	}

	@Override
	protected String getModelVersion() {
		return "";
	}

	@Override
	protected String getRestRoute() {
		return "/tag";
	}

	@Override
	public void initialize(UimaContext aContext) throws ResourceInitializationException {
		super.initialize(aContext);

		super.initialize(aContext);
		mappingProvider = new MappingProvider();
		mappingProvider.setDefault(MappingProvider.LOCATION, "classpath:/org/hucompute/textimager/uima/biofid/flair/ner-biofid.map");
		mappingProvider.setDefault(MappingProvider.BASE_TYPE, NamedEntity.class.getName());
		mappingProvider.setDefault(MappingProvider.LANGUAGE, "de");
	}

	/**
	 * The unique key to identify the annotation of the dynamic batch size inside of the jCas Object
	 */
	static public String DYNAMIC_CONFIGURATION_BATCH_SIZE_KEY = "biofid_flair.dynamic_configuration.batch_size";

	/**
	 * Sets the batch size as annotation inside of the jCAS
	 * @param aJCAS The jCAS Object to annotate
	 * @param batch_size The batch size to write into the jCas Object
	 */
	public static void set_batch_size(JCas aJCAS, int batch_size) {
		MetaDataStringField field = new MetaDataStringField(aJCAS);
		field.setValue(Integer.toString(batch_size));
		//Set the key to something constant
		field.setKey(BiofidFlair.DYNAMIC_CONFIGURATION_BATCH_SIZE_KEY);
		field.addToIndexes();
	}

	@Override
	protected JSONObject buildJSON(JCas aJCas) {
		// Format:
		// {"sentences":["Das ist ein Test am 08.12.2020, einem Dienstag."]}

		// Collect all sentences texts from CAS in JSON array
		JSONArray sentences = new JSONArray();
		JCasUtil.select(aJCas, Sentence.class)
				.stream()
				.map(Annotation::getCoveredText)
				.forEach(sentences::put);

		//Default batch size is 16
		int batch_size = 16;

		// Extract the batch size from the jCas if there is any
		for (MetaDataStringField i : JCasUtil.select(aJCas, MetaDataStringField.class)) {
			if (i.getKey().trim().equals(BiofidFlair.DYNAMIC_CONFIGURATION_BATCH_SIZE_KEY.trim())) {
				batch_size = parseInt(i.getValue().trim());
				if(batch_size > 128) {
					batch_size = 128;
				}
			}
		}

		// Pack in "sentences" object
		JSONObject payload = new JSONObject();
		payload.put("sentences", sentences);
		payload.put("batch_size",batch_size);

		return payload;
	}

	@Override
	protected void updateCAS(JCas aJCas, JSONArray jsonResult) throws AnalysisEngineProcessException {
		mappingProvider.configure(aJCas.getCas());

		// Add taxon info to all taxons
		Collection<Taxon> taxons = JCasUtil.select(aJCas, Taxon.class);
		for (Taxon taxon : taxons) {
			taxon.setValue("Taxon;Gazetteer;" + taxon.getValue());
		}

		// Result is a list of results, sent to different endpoints that handle different models
		for (Object result : jsonResult) {
			JSONObject jsonResultObject = (JSONObject) result;
			JSONArray jsonSentences = jsonResultObject.getJSONArray("results");

			int currentSentenceInd = 0;
			ArrayList<Sentence> sentences = new ArrayList(JCasUtil.select(aJCas, Sentence.class));

			System.out.println("cas sentences: " + sentences.size());
			System.out.println("json sentences: " + jsonSentences.length());

			// merge flair results in cas
			// make sure to only add if no overlapping taxon is found
			for (Object sentenceObj : jsonSentences) {
				JSONObject sentence = (JSONObject) sentenceObj;

				// Find this sentence in cas
				int textBegin = -1;
				Sentence currentSentence = sentences.get(currentSentenceInd);
				if (currentSentence != null) {
					textBegin = currentSentence.getBegin();
				}
				currentSentenceInd += 1;

				String jsonSentence = sentence.getString("text");
				System.out.println("cas: " + currentSentence.getCoveredText());
				System.out.println("json: " + jsonSentence);

				if (textBegin < 0) {
					// this should not happen!
					throw new AnalysisEngineProcessException(new Exception("could not match text begin, this should not happen!"));
				}

				// add flair biofid annotations
				for (Object spanObj : sentence.getJSONArray("spans")) {
					JSONObject span = (JSONObject) spanObj;

					int spanBegin = textBegin + span.getInt("begin");
					int spanEnd = textBegin + span.getInt("end");
					String spanText = span.getString("text");

					// check for overlapping taxons...
					boolean ok = true;
					for (Taxon taxon : taxons) {
						if ((spanBegin >= taxon.getBegin() && spanBegin <= taxon.getEnd())
								|| (spanEnd >= taxon.getBegin() && spanEnd <= taxon.getEnd())) {
							// do not add flair result
							System.out.println("not adding flair span: " + spanBegin + "," + spanEnd + ": " + spanText);
							ok = false;
							break;
						}
					}

					for (Object labelObj : span.getJSONArray("labels")) {
						JSONObject label = (JSONObject) labelObj;

						double labelScore = label.getDouble("score");
						String classValue = label.getString("value");

						// get class from label
						try {
							Type tagType = mappingProvider.getTagType(classValue);
							AnnotationFS annotation = aJCas.getCas().createAnnotation(tagType, spanBegin, spanEnd);
							annotation.setStringValue(tagType.getFeatureByBaseName("value"), classValue + ";Flair" + (ok ? "" : "-REMOVED") + ";score=" + labelScore);
							aJCas.addFsToIndexes(annotation);
						} catch (ClassCastException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
	}
}
