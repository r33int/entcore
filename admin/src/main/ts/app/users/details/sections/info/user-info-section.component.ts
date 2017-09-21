import { Component, Input, ViewChild, ChangeDetectorRef, OnInit } from '@angular/core'
import { NgModel } from '@angular/forms'

import { AbstractSection } from '../abstract.section'
import { SpinnerService, NotifyService, PlateformeInfoService } from '../../../../core/services'

@Component({
    selector: 'user-info-section',
    template: `
    <panel-section section-title="users.details.section.infos">
        <form-field label="profile">
            <span>{{ user.type | translate }}</span>
        </form-field>
        <form-field label="login">
            <span>{{ details.login }}</span>
        </form-field>
        <form-field label="activation.code" *ngIf="details.activationCode">
            <div>
                <input type="text" [(ngModel)]="details.activationCode" #codeInput="ngModel" 
                    [attr.placeholder]="'activation.code.placeholder' | translate"
                    required minlength="4">
                <form-errors [control]="codeInput"></form-errors>
                <button [disabled]="codeInput.invalid">
                    <s5l>change.activation.code</s5l>
                    <i class="fa fa-refresh"></i>
                </button>
            </div>
        </form-field>
        <form-field label="id">
            <span>{{ user.id }}</span>
        </form-field>
        <form-field label="source">
            <span>{{ details.source }}</span>
        </form-field>
        <form-field label="administration" *ngIf="!user.deleteDate">
            <button class= "noflex"
                *ngIf="!details.functions || !details.functions[0] || !details.functions[0][0]" 
                (click)="addAdml()">
                <s5l>adml.add</s5l>
                <i class="fa fa-cog"></i>
            </button>
            <div *ngFor="let function of details.functions">
                {{ function[0] | translate }}
                <span *ngIf="function[1] && function[1].length > 0 && getStructure(function[1][0])">
                    ({{ 'structure.or.more' | translate:{ head: getStructure(function[1][0]).name, rest: function[1].length - 1 } }})
                </span>
                <span *ngIf="function[1] && function[1].length > 0 && !getStructure(function[1][0])">
                    ({{ 'member.of.n.structures' | translate:{ count: function[1].length } }})
                </span>
                <button *ngIf="details.isAdml()" 
                    (click)="removeAdml()">
                    <s5l>adml.remove</s5l>
                    <i class="fa fa-cog"></i>
                </button>
            </div>
        </form-field>
        <form-field label="send.reset.password" *ngIf="!details.activationCode">
            <div>
                <div class="sendPassword">
                    <input type="email" [(ngModel)]="passwordResetMail" name="passwordResetMail" 
                        [attr.placeholder]="'send.reset.password.email.placeholder' | translate"
                        #passwordMailInput="ngModel" [pattern]="emailPattern">
                    <button (click)="sendResetPasswordMail(passwordResetMail)" 
                        [disabled]="!passwordResetMail || passwordMailInput.errors">
                        <span><s5l>send.reset.password.button</s5l></span>
                        <i class="fa fa-envelope"></i>
                    </button>
                </div>

                <div class="sendPassword" *ngIf="smsModule">
                    <input type="tel" [(ngModel)]="passwordResetMobile" name="passwordResetMobile" 
                        [attr.placeholder]="'send.reset.password.mobile.placeholder' | translate"
                        #passwordMobileInput="ngModel">
                    <button class="mobile"
                        (click)="sendResetPasswordMobile(passwordResetMobile)" 
                        [disabled]="!passwordResetMobile || passwordMobileInput.errors ">
                        <span><s5l>send.reset.password.button</s5l></span>
                        <i class="fa fa-mobile"></i>
                    </button>
                </div>
            </div>
        </form-field>
    </panel-section>`,
    inputs: ['user', 'structure']
})
export class UserInfoSection extends AbstractSection implements OnInit {
    passwordResetMail: string
    passwordResetMobile: string
    smsModule: boolean

    constructor(
        private ns: NotifyService,
        private spinner: SpinnerService,
        private cdRef: ChangeDetectorRef) {
        super()
    }

    ngOnInit() {
        this.passwordResetMail = this.details.email
        this.passwordResetMobile = this.details.mobile
        PlateformeInfoService.isSmsModule().then(res => {
            this.smsModule = res
            this.cdRef.markForCheck()
        })
    }

    protected onUserChange(){
        if(!this.details.activationCode) {
            this.passwordResetMail = this.details.email
            this.passwordResetMobile = this.details.mobile
        }
    }

    addAdml() {
        this.spinner.perform('portal-content', this.details.addAdml(this.structure.id))
            .then(res => {
                this.ns.success({
                        key: 'notify.user.add.adml.content',
                        parameters: {user: this.user.firstName + ' ' + this.user.lastName}
                    }, 'notify.user.add.adml.title')
            }).catch(err => {
                this.ns.error({
                        key: 'notify.user.add.adml.error.content',
                        parameters: {user: this.user.firstName + ' ' + this.user.lastName}
                    }, 'notify.user.add.adml.error.title', err)
            })
    }

    removeAdml() {
        this.spinner.perform('portal-content', this.details.removeAdml())
            .then(res => {
                this.ns.success({
                        key: 'notify.user.remove.adml.content',
                        parameters: {user: this.user.firstName + ' ' + this.user.lastName}
                    }, 'notify.user.remove.adml.title')
            }).catch(err => {
                this.ns.error({
                        key: 'notify.user.remove.adml.error.content',
                        parameters: {user: this.user.firstName + ' ' + this.user.lastName}
                    }, 'notify.user.remove.adml.error.title', err)
            })
    }

    sendResetPasswordMail(email: string) {
        this.spinner.perform('portal-content', this.details.sendResetPassword({type: 'email', value: email}))
            .then(res => {
                this.ns.success({
                        key: 'notify.user.sendResetPassword.email.content',
                        parameters: {
                            user: this.user.firstName + ' ' + this.user.lastName,
                            mail: email
                        }
                    }, 'notify.user.sendResetPassword.email.title')
            })
            .catch(err => {
                this.ns.error({
                        key: 'notify.user.sendResetPassword.email.error.content',
                        parameters: {
                            user: this.user.firstName + ' ' + this.user.lastName,
                            mail: email   
                        }
                    }, 'notify.user.sendResetPassword.email.error.title', err)
            })
    }

    sendResetPasswordMobile(mobile: string) {
        this.spinner.perform('portal-content', this.details.sendResetPassword({type: 'mobile', value: mobile}))
            .then(res => {
                this.ns.success({
                        key: 'notify.user.sendResetPassword.mobile.content',
                        parameters: {
                            user: this.user.firstName + ' ' + this.user.lastName,
                            mobile: mobile
                        }
                    }, 'notify.user.sendResetPassword.mobile.title')
            })
            .catch(err => {
                this.ns.error({
                        key: 'notify.user.sendResetPassword.mobile.error.content',
                        parameters: {
                            user: this.user.firstName + ' ' + this.user.lastName,
                            mobile: mobile   
                        }
                    }, 'notify.user.sendResetPassword.mobile.error.title', err)
            })
    }
}