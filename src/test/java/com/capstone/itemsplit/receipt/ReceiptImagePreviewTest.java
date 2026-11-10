package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.auth.JwtTokenProvider;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ReceiptImagePreviewTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired JwtTokenProvider tokens;
    @Autowired ObjectMapper json;

    @Test void onlyMembersCanIssueUrlsAndSignedImageReturnsOriginalBytes() throws Exception {
        User owner=users.save(User.create(UUID.randomUUID()+"@test.com","pw","owner"));
        User outsider=users.save(User.create(UUID.randomUUID()+"@test.com","pw","outsider"));
        Room room=rooms.save(Room.create("preview",owner));members.save(RoomMember.create(room,owner));
        String token="Bearer "+tokens.createAccessToken(owner).accessToken();
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aP1kAAAAASUVORK5CYII=");
        var response=mvc.perform(multipart("/api/rooms/"+room.getId()+"/receipts/image")
            .file(new MockMultipartFile("file","receipt.png","image/png",png)).header("Authorization",token))
            .andExpect(status().isCreated()).andReturn().getResponse();
        long receipt=json.readTree(response.getContentAsString()).at("/data/receiptId").asLong();
        String path="/api/rooms/"+room.getId()+"/receipts/"+receipt+"/images";
        var images=json.readTree(mvc.perform(get(path).header("Authorization",token)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(images.size()).isEqualTo(1);
        assertThat(images.get(0).path("order").asInt()).isEqualTo(1);
        assertThat(images.toString()).doesNotContain("storedPath");
        String url=images.get(0).path("url").asText();
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control","private, no-store")).andExpect(content().bytes(png));
        mvc.perform(get(url.replaceFirst("sig=.","sig=x"))).andExpect(status().isForbidden());
        mvc.perform(get(url.replaceFirst("expires=\\d+","expires=1"))).andExpect(status().isForbidden());
        mvc.perform(get(url.substring(0,url.indexOf('?')))).andExpect(status().isBadRequest());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+tokens.createAccessToken(outsider).accessToken())).andExpect(status().isForbidden());
        mvc.perform(get(path+"?token=shared-token")).andExpect(status().isUnauthorized());
        mvc.perform(get(path.replace("/receipts/"+receipt,"/receipts/999999")).header("Authorization",token)).andExpect(status().isNotFound());
        members.save(RoomMember.create(room,outsider));
        mvc.perform(get(path).header("Authorization","Bearer "+tokens.createAccessToken(outsider).accessToken())).andExpect(status().isOk());
    }
}
