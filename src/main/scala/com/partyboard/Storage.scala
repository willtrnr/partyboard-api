package com.partyboard

import java.io.{InputStream, FileInputStream, ByteArrayInputStream}
import java.util.Arrays

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.InputStreamContent
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.storage.model.Bucket
import com.google.api.services.storage.model.ObjectAccessControl
import com.google.api.services.storage.model.StorageObject
import com.google.api.services.storage.StorageScopes
import com.google.api.services.storage.{Storage => CloudStorage}

object Storage {
    val credential = GoogleCredential.fromStream(new FileInputStream("Servers-9e3a3b0bf9ba.json"))createScoped(StorageScopes.all)
    val transport = GoogleNetHttpTransport.newTrustedTransport
    val jsonFactory = JacksonFactory.getDefaultInstance
    val service = new CloudStorage.Builder(transport, jsonFactory, credential).setApplicationName("PartyBoard/1.0").build

    def getBucket(name: String): Bucket = {
        val request = service.buckets.get(name)
        request.setProjection("full")
        request.execute
    }

    def upload(bucket: String, fileName: String, contentType: String, stream: InputStream): String = {
        val contentStream = new InputStreamContent(contentType, stream)
        val metadata = new StorageObject().setName(fileName).setAcl(Arrays.asList(new ObjectAccessControl().setEntity("allUsers").setRole("READER")))
        val request = service.objects.insert(bucket, metadata, contentStream)
        request.execute.getMediaLink
    }
}
