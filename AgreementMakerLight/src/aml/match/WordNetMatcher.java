/******************************************************************************
* Copyright 2013-2014 LASIGE                                                  *
*                                                                             *
* Licensed under the Apache License, Version 2.0 (the "License"); you may     *
* not use this file except in compliance with the License. You may obtain a   *
* copy of the License at http://www.apache.org/licenses/LICENSE-2.0           *
*                                                                             *
* Unless required by applicable law or agreed to in writing, software         *
* distributed under the License is distributed on an "AS IS" BASIS,           *
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.    *
* See the License for the specific language governing permissions and         *
* limitations under the License.                                              *
*                                                                             *
*******************************************************************************
* Matches two Ontologies by finding literal full-name matches between their   *
* Lexicons after extension with the WordNet.                                  *
*                                                                             *
* @author Daniel Faria                                                        *
* @date 01-02-2014                                                            *
******************************************************************************/
package aml.match;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

import aml.match.Matcher;
import aml.ontology.Lexicon;
import aml.ontology.Ontology;
import aml.util.StringParser;

import edu.smu.tspell.wordnet.Synset;
import edu.smu.tspell.wordnet.WordNetDatabase;

public class WordNetMatcher implements Matcher
{
	
//Attributes

	//The WordNet Interface
	private WordNetDatabase WordNet;
	//The path to the WordNet database
	private final String PATH = "store/knowledge/wordnet/";
	//The type of lexical entry generated by this LexiconExtender
	private final String TYPE = "externalMatch";
	//The source of this LexiconExtender
	private final String SOURCE = "WordNet";
	//The confidence score of WordNet
	private final double CONFIDENCE = 0.9;
	
//Constructors

	/**
	 * Constructs a new WordNetMatcher with the given CONFIDENCE
	 */
	public WordNetMatcher()
	{
		//Setup the wordnet database directory
		String path = new File(PATH).getAbsolutePath();
		System.setProperty("wordnet.database.dir", path);
		//Instantiate WordNet
		WordNet = WordNetDatabase.getFileInstance();
	}

//Public Methods

	@Override
	public Alignment extendAlignment(Alignment a, double thresh)
	{
		Ontology source = a.getSource();
		Ontology target = a.getTarget();
		Alignment ext = new Alignment(source,target);
		Lexicon ext1 = getExtensionLexicon(source.getLexicon(),thresh);
		Lexicon ext2 = getExtensionLexicon(target.getLexicon(),thresh);
		Vector<Mapping> maps = match(ext1, ext2, thresh);
		for(Mapping m : maps)
			if(!a.containsConflict(m))
				ext.add(m);
		return ext;
	}
	
	/**
	 * @param s: the String to search in WordNet
	 * @return the set of word forms for the given String
	 */
	public HashSet<String> getAllWordForms(String s)
	{
		HashSet<String> wordForms = new HashSet<String>();

		//Look for the name on WordNet
		Synset[] synsets = WordNet.getSynsets(s);
		//For each Synset found
		for(Synset ss : synsets)
		{
			//Get the WordForms
			String[] words = ss.getWordForms();
			//And add each one to the Lexicon
			for(String w : words)
				if(!w.trim().equals(""))
					wordForms.add(w);
		}
		return wordForms;
	}

	@Override
	public Alignment match(Ontology source, Ontology target, double thresh)
	{
		Lexicon ext1 = getExtensionLexicon(source.getLexicon(),thresh);
		Lexicon ext2 = getExtensionLexicon(target.getLexicon(),thresh);
		Alignment a = new Alignment(source, target);
		a.addAll(match(ext1, ext2, thresh));
		return a;
	}
	
//Private Methods

	//Returns a copy of the given Lexicon, extended with WordNet
	private Lexicon getExtensionLexicon(Lexicon l, double thresh)
	{
		Lexicon ext = new Lexicon(l);
		Set<String> names = l.getNames();
		//Iterate through the original Lexicon names
		for(String s : names)
		{
			//We don't match formulas to WordNet
			if(StringParser.isFormula(s))
				continue;
			//Find all wordForms in WordNet for each name
			HashSet<String> wordForms = getAllWordForms(s);
			int size = wordForms.size();
			if(size == 0)
				continue;
			double conf = CONFIDENCE - 0.01*size;
			if(conf < thresh)
				continue;
			Set<Integer> terms = l.getInternalTerms(s);
			//Add each term with the name to the extension Lexicon
			for(Integer i : terms)
			{
				double weight = conf * l.getWeight(s, i);
				if(weight < thresh)
					continue;
				for(String w : wordForms)
					ext.add(i, w, TYPE, SOURCE, weight);
			}
		}
		return ext;
	}
	
	//Matches two given Lexicons (after extension with WordNet)
	private Vector<Mapping> match(Lexicon source, Lexicon target, double thresh)
	{
		Vector<Mapping> maps = new Vector<Mapping>(0,1);
		Lexicon larger, smaller;
		//To minimize iterations, we want to iterate through the
		//ontology with the smallest Lexicon
		boolean sourceIsSmaller = (source.nameCount() <= target.nameCount());
		double weight, similarity;
		if(sourceIsSmaller)
		{
			smaller = source;
			larger = target;
		}
		else
		{
			smaller = target;
			larger = source;
		}
		//Get the smaller ontology names
		Set<String> names = smaller.getNames();
		for(String s : names)
		{
			//Get all term indexes for the name in both ontologies
			Set<Integer> largerIndexes = larger.getTerms(s);
			Set<Integer> smallerIndexes = smaller.getTerms(s);
			if(largerIndexes == null)
				continue;
			//Otherwise, match all indexes
			for(Integer i : smallerIndexes)
			{
				//Get the weight of the name for the term in the smaller lexicon
				weight = smaller.getCorrectedWeight(s, i);
				String smallerSource = smaller.getSource(s, i);
				for(Integer j : largerIndexes)
				{
					String largerSource = larger.getSource(s, j);
					//We only consider matches involving at least one WordNet synonym
					//and not envolving any external synonyms
					boolean check = (smallerSource.equals(SOURCE) || largerSource.equals(SOURCE)) ||
							(smallerSource.equals(SOURCE) && largerSource.equals("")) ||
							(smallerSource.equals("") && largerSource.equals(SOURCE));
					if(!check)
						continue;
					//Get the weight of the name for the term in the larger lexicon
					similarity = larger.getCorrectedWeight(s, j);
					//Then compute the similarity, by multiplying the two weights
					similarity *= weight;
					//If the similarity is above threshold
					if(similarity >= thresh)
					{
						//Add the mapping, taking into account the order of the ontologies
						if(sourceIsSmaller)
							maps.add(new Mapping(i, j, similarity));
						else
							maps.add(new Mapping(j, i, similarity));
					}
				}
			}
		}
		return maps;	
	}
}