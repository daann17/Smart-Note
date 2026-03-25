package com.smartnote.service;

import com.smartnote.entity.Tag;
import com.smartnote.entity.User;
import com.smartnote.repository.TagRepository;
import com.smartnote.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class TagService {

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private UserRepository userRepository;

    public List<Tag> getUserTags(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return tagRepository.findByUserId(user.getId());
    }

    @Transactional
    public Tag createTag(String username, String tagName) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Optional<Tag> existingTag = tagRepository.findByUserIdAndName(user.getId(), tagName);
        if (existingTag.isPresent()) {
            return existingTag.get();
        }

        Tag newTag = new Tag();
        newTag.setUser(user);
        newTag.setName(tagName);
        return tagRepository.save(newTag);
    }

    @Transactional
    public void deleteTag(String username, Long tagId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));
        
        if (!tag.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Not authorized to delete this tag");
        }
        
        tagRepository.delete(tag);
    }
}
