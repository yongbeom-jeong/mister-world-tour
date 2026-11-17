package com.misterworldtour.travel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 실제 여행자 한 명. 신청 고객 본인도 여기에 포함한다. */
@Entity
@Table(name = "traveler")
public class Traveler {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id")
    private TravelApplication application;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String contact = "";

    @Column(name = "is_applicant", nullable = false)
    private boolean applicant;

    protected Traveler() {
    }

    Traveler(TravelApplication application, String name, String contact, boolean applicant) {
        this.application = application;
        this.name = name;
        this.contact = contact == null ? "" : contact;
        this.applicant = applicant;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getContact() {
        return contact;
    }

    public boolean isApplicant() {
        return applicant;
    }
}
