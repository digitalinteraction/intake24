/*
This file is part of Intake24.

© Crown copyright, 2012, 2013, 2014.

This software is licensed under the Open Government Licence 3.0:

http://www.nationalarchives.gov.uk/doc/open-government-licence/
*/

package net.scran24.datastore;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.scran24.datastore.shared.UserRecord;

import au.com.bytecode.opencsv.CSVReader;

public class UserRecordCSV {

	private static void checkHeader(String[] header) throws IOException {
		if (header.length < 2)
			throw new IOException("Incorrect number of columns in header: at least 2 required (username and password)");
		else if (!(header[0].toLowerCase().equals("user name") && header[1].toLowerCase().equals("password")))
			throw new IOException(
					"Incorrect header: first two columns must be \"user name\" and \"password\" - are you using the correct spreadsheet template?");
		else
			for (int i = 2; i < header.length; i++)
				if (header[i].isEmpty())
					throw new IOException("Column " + (i + 1) + " has no title: please add a title in the header row or delete the column");
	}

	private static void checkStructure(List<String[]> rows) throws IOException {
		int line = 1;
		for (String[] row : rows) {
			if (row.length < 2)
				throw new IOException("Incorrect number of fields in row " + line + ": at least 2 required, only " + row.length + "found");
			else if (row[0].isEmpty() || row[1].isEmpty())
				throw new IOException("User name or password is empty in row " + line);
			line++;
		}
	}
	
	private static boolean containsWhitespace(String s) {
		return s.contains(" ") || s.contains("\t") || s.contains("\n");
	}
	
	private static void checkWhitespace(List<UserRecord> userRecords) throws IOException {
		for (UserRecord r : userRecords)
			if (containsWhitespace(r.username) || containsWhitespace(r.password))
				throw new IOException("Spaces and tabs are not allowed in user names or passwords (for user " + r.username + ")");		
	}

	private static void checkUniqueness(List<UserRecord> userRecords) throws IOException {
		Set<String> uniqueSet = new HashSet<String>();
		Set<String> duplicateSet = new LinkedHashSet<String>();

		for (UserRecord r : userRecords)
			if (uniqueSet.contains(r.username))
				duplicateSet.add(r.username);
			else
				uniqueSet.add(r.username);

		if (!duplicateSet.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			boolean first = true;
			for (String s : duplicateSet) {
				if (first)
					first = false;
				else
					sb.append(", ");
				sb.append(s);
			}

			throw new IOException("Duplicate user name(s) found: " + sb.toString());
		}
	}

	public static List<UserRecord> fromCSV(InputStream input) throws IOException {
		CSVReader reader = new CSVReader(new InputStreamReader(input));

		String[] header = reader.readNext();
		List<String[]> rows = reader.readAll();
		reader.close();

		if (rows.size() < 1)
			throw new IOException("File is empty or in incorrect format");

		checkHeader(header);
		checkStructure(rows);

		List<UserRecord> result = new ArrayList<UserRecord>();

		for (String[] r : rows) {
			Map<String, String> customFields = new HashMap<String, String>();

			for (int i = 2; i < header.length; i++) {
				if (i < r.length)
					customFields.put(header[i], r[i]);
			}

			result.add(new UserRecord(r[0], r[1], customFields));
		}
		
		checkWhitespace(result);

		checkUniqueness(result);

		return result;
	}
}